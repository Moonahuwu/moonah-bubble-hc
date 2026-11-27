package moonahmc.bubble;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import moonahmc.bubble.net.PayloadTypeRegistry;
import moonahmc.bubble.net.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Run goals: a game mode is a list of stages (Overworld, Nether, Aether, Twilight Forest, Deep Dark, Cataclysm, End...)
 * plus optional sub-goals. Everyone shares the progress. It's shown in the top-right HUD; clearing a stage pays Strange
 * Matter, finishing the mode is a victory (Eidolons). Modes are in config/bubble-goals.json; the mode for the next world
 * is picked in the Bubble menu's Next run tab.
 *
 * Objective types: dimension, advancement, kill (entities list, count, distinct), item (team total), structure, custom
 * (case_opened, lucky_block, apothic_roll).
 */
public final class Goals {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	record Objective(String type, String text, List<String> ids, int count, boolean distinct, int reward, String caseId, int cases) {
	}

	record Stage(String name, List<Objective> objectives, int reward, String caseId, int cases) {
	}

	record Mode(String id, String name, String description, List<Stage> stages, List<Objective> subgoals) {
	}

	/** Facts collected since the run started; objectives are checked against these. */
	static final class Facts {
		String mode = "full_run";
		int stage;
		Set<String> done = new HashSet<>();
		Set<String> dimensions = new HashSet<>();
		Map<String, Integer> kills = new HashMap<>();
		Set<String> structures = new HashSet<>();
		Map<String, Integer> custom = new HashMap<>();
		boolean victory;
		int stagesCleared;
	}

	public record Line(String text, boolean done, int progress, int target) {
	}

	/** Server to client: what the HUD shows. */
	public record State(String mode, int stage, int stages, String stageName, List<Line> objectives, List<Line> subgoals,
						List<String> modifiers, boolean victory) implements CustomPacketPayload {
		public static final Type<State> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "goals_state"));
		public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.ofMember((s, buf) -> {
			buf.writeUtf(s.mode, 64);
			buf.writeVarInt(s.stage);
			buf.writeVarInt(s.stages);
			buf.writeUtf(s.stageName, 64);
			writeLines(buf, s.objectives);
			writeLines(buf, s.subgoals);
			buf.writeVarInt(s.modifiers.size());
			s.modifiers.forEach(m -> buf.writeUtf(m, 128));
			buf.writeBoolean(s.victory);
		}, buf -> {
			String mode = buf.readUtf(64);
			int stage = buf.readVarInt();
			int stages = buf.readVarInt();
			String name = buf.readUtf(64);
			List<Line> obj = readLines(buf);
			List<Line> sub = readLines(buf);
			List<String> mods = new ArrayList<>();
			int n = Math.min(buf.readVarInt(), 16);
			for (int i = 0; i < n; i++) {
				mods.add(buf.readUtf(128));
			}
			return new State(mode, stage, stages, name, obj, sub, mods, buf.readBoolean());
		});

		private static void writeLines(RegistryFriendlyByteBuf buf, List<Line> lines) {
			buf.writeVarInt(lines.size());
			for (Line l : lines) {
				buf.writeUtf(l.text, 128);
				buf.writeBoolean(l.done);
				buf.writeVarInt(l.progress);
				buf.writeVarInt(l.target);
			}
		}

		private static List<Line> readLines(RegistryFriendlyByteBuf buf) {
			List<Line> out = new ArrayList<>();
			int n = Math.min(buf.readVarInt(), 32);
			for (int i = 0; i < n; i++) {
				out.add(new Line(buf.readUtf(128), buf.readBoolean(), buf.readVarInt(), buf.readVarInt()));
			}
			return out;
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	private static final Map<String, Mode> modes = new LinkedHashMap<>();
	private static Facts facts = new Facts();
	private static MinecraftServer server;
	private static Path stateFile;
	private static boolean dirty;

	private Goals() {
	}

	static void register(PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c, PayloadTypeRegistry<RegistryFriendlyByteBuf> c2s) {
		s2c.register(State.ID, State.CODEC);
		s2c.register(Config.ID, Config.CODEC);
		c2s.register(Edit.ID, Edit.CODEC);
	}

	// ------------------------------------------------------------------ in-game editor (ops, Bubble menu > Admin)

	public static final int EDIT_REQUEST = 0;
	public static final int EDIT_ADD_OBJECTIVE = 1;
	public static final int EDIT_REMOVE_OBJECTIVE = 2;
	public static final int EDIT_ADD_STAGE = 3;
	public static final int EDIT_REMOVE_STAGE = 4;
	/** Switch this run to the mode (progress starts over for the new mode; kills, places etc. so far still count). */
	public static final int EDIT_USE_NOW = 5;
	/** "stage" for the bonus goals list. */
	public static final int BONUS = -1;
	public static final List<String> TYPES = List.of("kill", "item", "dimension", "advancement", "structure", "custom");
	public static final List<String> CUSTOM_EVENTS = List.of("case_opened", "lucky_block", "apothic_roll");

	/** Server to client: the "modes" part of config/bubble-goals.json for the editor, this run's mode, and the cases. */
	public record Config(String json, String runMode, List<String> caseIds, List<String> caseNames) implements CustomPacketPayload {
		public static final Type<Config> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "goals_config"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Config> CODEC = StreamCodec.ofMember((c, buf) -> {
			buf.writeUtf(c.json, 1 << 20);
			buf.writeUtf(c.runMode, 64);
			buf.writeVarInt(c.caseIds.size());
			for (int i = 0; i < c.caseIds.size(); i++) {
				buf.writeUtf(c.caseIds.get(i), 64);
				buf.writeUtf(c.caseNames.get(i), 64);
			}
		}, buf -> {
			String json = buf.readUtf(1 << 20);
			String run = buf.readUtf(64);
			int n = Math.min(buf.readVarInt(), 64);
			List<String> ids = new ArrayList<>();
			List<String> names = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				ids.add(buf.readUtf(64));
				names.add(buf.readUtf(64));
			}
			return new Config(json, run, ids, names);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client to server: one edit. stage = -1 is the bonus list; json is the new objective, or the new stage's name. */
	public record Edit(int action, String mode, int stage, int index, String json) implements CustomPacketPayload {
		public static final Type<Edit> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "goals_edit"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Edit> CODEC = StreamCodec.ofMember((e, buf) -> {
			buf.writeVarInt(e.action);
			buf.writeUtf(e.mode, 64);
			buf.writeVarInt(e.stage + 1);
			buf.writeVarInt(e.index);
			buf.writeUtf(e.json, 4096);
		}, buf -> new Edit(buf.readVarInt(), buf.readUtf(64), buf.readVarInt() - 1, buf.readVarInt(), buf.readUtf(4096)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	private static JsonObject configRoot;

	static void sendConfig(ServerPlayer p) {
		if (configRoot == null || !ServerPlayNetworking.canSend(p, Config.ID)) {
			return;
		}
		List<String> ids = new ArrayList<>(Gamble.ids());
		ServerPlayNetworking.send(p, new Config(configRoot.getAsJsonObject("modes").toString(), facts.mode, ids,
				ids.stream().map(Gamble::caseName).toList()));
	}

	private static void editFail(ServerPlayer p, String why) {
		p.displayClientMessage(Component.literal(why).withStyle(ChatFormatting.RED), true);
		sendConfig(p);
	}

	static void edit(ServerPlayer p, Edit e) {
		if (!p.hasPermissions(2) || configRoot == null) {
			return;
		}
		if (e.action == EDIT_REQUEST) {
			sendConfig(p);
			return;
		}
		JsonObject modesJson = configRoot.getAsJsonObject("modes");
		if (!modesJson.has(e.mode)) {
			editFail(p, "That mode doesn't exist any more.");
			return;
		}
		JsonObject mode = modesJson.getAsJsonObject(e.mode);
		JsonArray stages = mode.getAsJsonArray("stages");
		boolean current = e.mode.equals(facts.mode);
		String who = p.getGameProfile().getName();
		String what;
		switch (e.action) {
			case EDIT_ADD_OBJECTIVE -> {
				JsonArray list = list(mode, stages, e.stage, true);
				if (list == null) {
					editFail(p, "Pick a stage first.");
					return;
				}
				JsonObject o;
				try {
					o = cleanObjective(JsonParser.parseString(e.json).getAsJsonObject());
				} catch (IllegalArgumentException ex) {
					editFail(p, ex.getMessage());
					return;
				}
				list.add(o);
				what = "added \"" + o.get("text").getAsString() + "\"";
			}
			case EDIT_REMOVE_OBJECTIVE -> {
				JsonArray list = list(mode, stages, e.stage, false);
				if (list == null || e.index < 0 || e.index >= list.size()) {
					editFail(p, "That objective is already gone.");
					return;
				}
				String text = list.get(e.index).getAsJsonObject().get("text").getAsString();
				list.remove(e.index);
				if (current) {
					remapDone(e.stage, e.index, false);
				}
				what = "removed \"" + text + "\"";
			}
			case EDIT_ADD_STAGE -> {
				String name = e.json.strip();
				if (name.isEmpty()) {
					editFail(p, "Give the stage a name.");
					return;
				}
				JsonObject st = new JsonObject();
				st.addProperty("name", name.length() > 48 ? name.substring(0, 48) : name);
				st.addProperty("reward", 100);
				st.add("objectives", new JsonArray());
				stages.add(st);
				what = "added the stage \"" + st.get("name").getAsString() + "\"";
			}
			case EDIT_REMOVE_STAGE -> {
				if (e.stage < 0 || e.stage >= stages.size()) {
					editFail(p, "That stage is already gone.");
					return;
				}
				if (stages.size() <= 1) {
					editFail(p, "A mode needs at least one stage.");
					return;
				}
				String name = stages.get(e.stage).getAsJsonObject().get("name").getAsString();
				stages.remove(e.stage);
				if (current) {
					remapDone(e.stage, 0, true);
				}
				what = "removed the stage \"" + name + "\"";
			}
			case EDIT_USE_NOW -> {
				facts.mode = e.mode;
				facts.stage = 0;
				facts.done.clear();
				facts.victory = false;
				what = "switched this run to " + mode.get("name").getAsString();
			}
			default -> {
				return;
			}
		}
		try {
			Files.writeString(FMLPaths.CONFIGDIR.get().resolve("bubble-goals.json"), GSON.toJson(configRoot));
		} catch (Exception ex) {
			Bubble.LOG.error("Could not save config/bubble-goals.json", ex);
		}
		parseModes(configRoot);
		saveFacts();
		Bubble.LOG.info("Goals: {} {} ({})", who, what, e.mode);
		for (ServerPlayer q : server.getPlayerList().getPlayers()) {
			if (q.hasPermissions(2)) {
				q.sendSystemMessage(Component.literal("[Goals] " + who + " " + what + " in " + mode.get("name").getAsString())
						.withStyle(ChatFormatting.GRAY));
				sendConfig(q);
			}
		}
		dirty = true;
		evaluate();
	}

	/** A stage's objectives, or the bonus list (made if missing when adding). */
	private static JsonArray list(JsonObject mode, JsonArray stages, int stage, boolean create) {
		if (stage == BONUS) {
			if (!mode.has("subgoals")) {
				if (!create) {
					return null;
				}
				mode.add("subgoals", new JsonArray());
			}
			return mode.getAsJsonArray("subgoals");
		}
		if (stage < 0 || stage >= stages.size()) {
			return null;
		}
		JsonObject st = stages.get(stage).getAsJsonObject();
		if (!st.has("objectives")) {
			st.add("objectives", new JsonArray());
		}
		return st.getAsJsonArray("objectives");
	}

	/** Checks a new objective from the editor and keeps only the fields we know. */
	private static JsonObject cleanObjective(JsonObject in) {
		String type = in.has("type") ? in.get("type").getAsString() : "";
		if (!TYPES.contains(type)) {
			throw new IllegalArgumentException("Unknown objective type: " + type);
		}
		String id = in.has("id") ? in.get("id").getAsString().strip().toLowerCase() : "";
		if (id.isEmpty()) {
			throw new IllegalArgumentException("Enter an ID (like minecraft:diamond).");
		}
		if (!id.contains(":") && !type.equals("custom")) {
			id = "minecraft:" + id;
		}
		ResourceLocation rl = ResourceLocation.tryParse(id);
		boolean ok = switch (type) {
			case "kill" -> rl != null && BuiltInRegistries.ENTITY_TYPE.containsKey(rl);
			case "item" -> rl != null && BuiltInRegistries.ITEM.containsKey(rl);
			case "advancement" -> rl != null && server.getAdvancements().get(rl) != null;
			case "structure" -> rl != null && server.registryAccess().registryOrThrow(Registries.STRUCTURE).containsKey(rl);
			case "dimension" -> rl != null;
			case "custom" -> CUSTOM_EVENTS.contains(id);
			default -> false;
		};
		if (!ok) {
			throw new IllegalArgumentException("No " + type + " called " + id + " on this server.");
		}
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		o.addProperty("id", id);
		int count = in.has("count") ? Math.max(1, Math.min(100000, in.get("count").getAsInt())) : 1;
		if (count > 1) {
			o.addProperty("count", count);
		}
		String text = in.has("text") ? in.get("text").getAsString().strip() : "";
		o.addProperty("text", text.isEmpty() ? id : text.length() > 60 ? text.substring(0, 60) : text);
		o.addProperty("reward", in.has("reward") ? Math.max(0, Math.min(1_000_000, in.get("reward").getAsInt())) : 0);
		if (in.has("case") && !in.get("case").getAsString().isEmpty()) {
			String c = in.get("case").getAsString();
			if (!Gamble.exists(c)) {
				throw new IllegalArgumentException("No case called " + c + ".");
			}
			o.addProperty("case", c);
			int cases = in.has("cases") ? Math.max(1, Math.min(64, in.get("cases").getAsInt())) : 1;
			if (cases > 1) {
				o.addProperty("cases", cases);
			}
		}
		return o;
	}

	/**
	 * Keeps the run's ticked-off objectives lined up after removing something from the current mode
	 * (they're stored by position: "s2o1" = stage 2 objective 1, "sub3" = bonus goal 3).
	 */
	private static void remapDone(int stage, int index, boolean wholeStage) {
		java.util.regex.Pattern sp = java.util.regex.Pattern.compile("s(\\d+)o(\\d+)");
		java.util.regex.Pattern bp = java.util.regex.Pattern.compile("sub(\\d+)");
		Set<String> out = new HashSet<>();
		for (String key : facts.done) {
			var sm = sp.matcher(key);
			var bm = bp.matcher(key);
			if (sm.matches()) {
				int s = Integer.parseInt(sm.group(1));
				int i = Integer.parseInt(sm.group(2));
				if (wholeStage) {
					if (s == stage) {
						continue;
					}
					out.add(s > stage ? "s" + (s - 1) + "o" + i : key);
				} else if (s == stage) {
					if (i == index) {
						continue;
					}
					out.add(i > index ? "s" + s + "o" + (i - 1) : key);
				} else {
					out.add(key);
				}
			} else if (bm.matches() && !wholeStage && stage == BONUS) {
				int i = Integer.parseInt(bm.group(1));
				if (i != index) {
					out.add(i > index ? "sub" + (i - 1) : key);
				}
			} else {
				out.add(key);
			}
		}
		facts.done = out;
		if (wholeStage && facts.stage > stage) {
			facts.stage--;
		}
	}

	public static List<String> modeIds() {
		return new ArrayList<>(modes.keySet());
	}

	/** The game mode this world is playing ("" if there are none). */
	static String currentModeName() {
		Mode m = mode();
		return m == null ? "" : m.name();
	}

	public static List<String> modeNames() {
		return modes.values().stream().map(Mode::name).toList();
	}

	static int stagesCleared() {
		return facts.stagesCleared;
	}

	static void init() {
		loadModes();
		ServerPlayNetworking.registerGlobalReceiver(Edit.ID, (payload, context) -> context.server().execute(() -> edit(context.player(), payload)));
		var bus = NeoForge.EVENT_BUS;
		bus.addListener((ServerStartedEvent e) -> {
			server = e.getServer();
			stateFile = server.getWorldPath(LevelResource.ROOT).resolve("bubble-goals.json");
			loadModes();
			loadFacts();
		});
		bus.addListener((ServerStoppedEvent e) -> {
			saveFacts();
			server = null;
		});
		bus.addListener((ServerTickEvent.Post e) -> {
			long t = e.getServer().getTickCount();
			if (t % 40 == 0) {
				scan(e.getServer());
			}
			if (dirty) {
				dirty = false;
				evaluate();
			}
		});
		bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
			if (e.getEntity() instanceof ServerPlayer p) {
				p.server.execute(() -> {
					facts.dimensions.add(p.level().dimension().location().toString());
					dirty = true;
					send(p);
				});
			}
		});
		bus.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
			facts.dimensions.add(e.getTo().location().toString());
			dirty = true;
		});
		bus.addListener((AdvancementEvent.AdvancementEarnEvent e) -> dirty = true);
		bus.addListener((LivingDeathEvent e) -> {
			if (e.getEntity().level().isClientSide || e.getEntity() instanceof Player || server == null) {
				return;
			}
			boolean byPlayer = e.getSource().getEntity() instanceof ServerPlayer;
			boolean near = e.getEntity().level().getNearestPlayer(e.getEntity(), 64) != null;
			if (byPlayer || near) {
				String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getEntity().getType()).toString();
				facts.kills.merge(id, 1, Integer::sum);
				dirty = true;
			}
		});
		bus.addListener((BlockEvent.BreakEvent e) -> {
			if ("lucky:lucky_block".equals(BuiltInRegistries.BLOCK.getKey(e.getState().getBlock()).toString())) {
				custom("lucky_block");
			}
		});
	}

	/** Bubble's own events: case_opened, lucky_block, apothic_roll. */
	static void custom(String event) {
		facts.custom.merge(event, 1, Integer::sum);
		dirty = true;
	}

	private static Mode mode() {
		Mode m = modes.get(facts.mode);
		return m != null ? m : modes.values().stream().findFirst().orElse(null);
	}

	// ------------------------------------------------------------------ checking

	/** Things we have to look for (structures players are standing in). */
	private static void scan(MinecraftServer s) {
		Mode m = mode();
		if (m == null) {
			return;
		}
		Set<String> wanted = new HashSet<>();
		for (Stage st : m.stages) {
			st.objectives.stream().filter(o -> o.type.equals("structure")).forEach(o -> wanted.addAll(o.ids));
		}
		m.subgoals.stream().filter(o -> o.type.equals("structure")).forEach(o -> wanted.addAll(o.ids));
		wanted.removeAll(facts.structures);
		if (!wanted.isEmpty()) {
			for (ServerPlayer p : s.getPlayerList().getPlayers()) {
				ServerLevel level = p.serverLevel();
				var reg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
				BlockPos pos = p.blockPosition();
				for (String id : wanted) {
					Structure structure = reg.get(ResourceLocation.tryParse(id));
					if (structure != null && level.structureManager().getStructureWithPieceAt(pos, structure).isValid()) {
						facts.structures.add(id);
						dirty = true;
					}
				}
			}
		}
		dirty = true; // items can change any time
	}

	private static int progress(Objective o) {
		return switch (o.type) {
			case "dimension" -> o.ids.stream().anyMatch(facts.dimensions::contains) ? 1 : 0;
			case "structure" -> o.ids.stream().anyMatch(facts.structures::contains) ? 1 : 0;
			case "kill" -> o.distinct ? (int) o.ids.stream().filter(id -> facts.kills.getOrDefault(id, 0) > 0).count()
					: o.ids.stream().mapToInt(id -> facts.kills.getOrDefault(id, 0)).sum();
			case "custom" -> o.ids.stream().mapToInt(id -> facts.custom.getOrDefault(id, 0)).sum();
			case "item" -> teamItems(o.ids);
			case "advancement" -> anyoneHas(o.ids) ? 1 : 0;
			default -> 0;
		};
	}

	private static int target(Objective o) {
		return switch (o.type) {
			case "dimension", "structure", "advancement" -> 1;
			default -> Math.max(1, o.count);
		};
	}

	private static int teamItems(List<String> ids) {
		if (server == null) {
			return 0;
		}
		Set<Item> items = new HashSet<>();
		for (String id : ids) {
			BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(id)).ifPresent(items::add);
		}
		int total = 0;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			for (ItemStack s : p.getInventory().items) {
				if (items.contains(s.getItem())) {
					total += s.getCount();
				}
			}
			for (ItemStack s : p.getInventory().armor) {
				if (items.contains(s.getItem())) {
					total += s.getCount();
				}
			}
			ItemStack off = p.getOffhandItem();
			if (items.contains(off.getItem())) {
				total += off.getCount();
			}
		}
		return total;
	}

	private static boolean anyoneHas(List<String> ids) {
		if (server == null) {
			return false;
		}
		for (String id : ids) {
			AdvancementHolder adv = server.getAdvancements().get(ResourceLocation.tryParse(id));
			if (adv == null) {
				continue;
			}
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (p.getAdvancements().getOrStartProgress(adv).isDone()) {
					return true;
				}
			}
		}
		return false;
	}

	/** Marks objectives done, pays rewards, moves to the next stage, sends the HUD. */
	private static void evaluate() {
		Mode m = mode();
		if (m == null || server == null) {
			return;
		}
		boolean changed = false;
		// sub-goals: any time
		for (int i = 0; i < m.subgoals.size(); i++) {
			Objective o = m.subgoals.get(i);
			String key = "sub" + i;
			if (!facts.done.contains(key) && progress(o) >= target(o)) {
				facts.done.add(key);
				reward(o.text, o.reward, o.caseId, o.cases, true);
				changed = true;
			}
		}
		// the current stage (a stage's objectives only count while it's the current one)
		while (!facts.victory && facts.stage < m.stages.size()) {
			Stage st = m.stages.get(facts.stage);
			boolean all = true;
			for (int i = 0; i < st.objectives.size(); i++) {
				Objective o = st.objectives.get(i);
				String key = "s" + facts.stage + "o" + i;
				if (facts.done.contains(key)) {
					continue;
				}
				if (progress(o) >= target(o)) {
					facts.done.add(key);
					reward(o.text, o.reward, o.caseId, o.cases, false);
					changed = true;
				} else {
					all = false;
				}
			}
			if (!all || st.objectives.isEmpty()) {
				break; // (an empty stage, mid-edit, waits for objectives instead of clearing itself)
			}
			// stage cleared
			facts.stagesCleared++;
			facts.stage++;
			changed = true;
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (st.reward > 0) {
					Matter.earn(p, st.reward);
				}
				giveCases(p, st.caseId, st.cases);
				String paid = rewardText(st.reward, st.caseId, st.cases);
				BubbleServer.title(p, Component.literal("STAGE CLEARED").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
						Component.literal(st.name + (paid.isEmpty() ? "" : "  ·  " + paid)).withStyle(ChatFormatting.WHITE), 10, 60, 20);
				p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 1f);
			}
			server.getPlayerList().broadcastSystemMessage(Component.literal("★ Stage cleared: " + st.name).withStyle(ChatFormatting.GOLD), false);
			if (BubbleServer.config.shopGoalSeconds > 0) {
				ShopWindows.openAll(BubbleServer.config.shopGoalSeconds, "stage cleared: " + st.name);
			}
			if (facts.stage >= m.stages.size()) {
				facts.victory = true;
				for (ServerPlayer p : server.getPlayerList().getPlayers()) {
					BubbleServer.title(p, Component.literal("VICTORY").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
							Component.literal(m.name + " complete!").withStyle(ChatFormatting.WHITE), 10, 100, 30);
				}
				server.getPlayerList().broadcastSystemMessage(Component.literal("✦ VICTORY! " + m.name + " complete. +10 Eidolons each.")
						.withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD), false);
				Eidolons.victory(facts.stagesCleared);
			}
		}
		if (changed) {
			saveFacts();
		}
		broadcast();
	}

	private static void reward(String text, int reward, String caseId, int cases, boolean sub) {
		String paid = rewardText(reward, caseId, cases);
		server.getPlayerList().broadcastSystemMessage(Component.literal((sub ? "◆ Bonus: " : "✔ ") + text
				+ (paid.isEmpty() ? "" : " (" + paid + " each)")).withStyle(sub ? ChatFormatting.AQUA : ChatFormatting.GREEN), false);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (reward > 0) {
				Matter.earn(p, reward);
			}
			giveCases(p, caseId, cases);
			p.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5f, sub ? 1.6f : 1.3f);
		}
	}

	/** "+100 Strange Matter, +1 Mod Case" (or "" for nothing). */
	private static String rewardText(int matter, String caseId, int cases) {
		List<String> parts = new ArrayList<>();
		if (matter > 0) {
			parts.add("+" + matter + " Strange Matter");
		}
		if (hasCase(caseId, cases)) {
			parts.add("+" + cases + " " + Gamble.caseName(caseId) + (cases > 1 ? "s" : ""));
		}
		return String.join(", ", parts);
	}

	private static boolean hasCase(String caseId, int cases) {
		return caseId != null && !caseId.isEmpty() && cases > 0 && Gamble.exists(caseId);
	}

	/** Quest reward: case items (right-click to open). Full inventory: dropped at your feet. */
	private static void giveCases(ServerPlayer p, String caseId, int cases) {
		if (!hasCase(caseId, cases)) {
			return;
		}
		ItemStack stack = CaseItem.of(caseId, cases);
		if (!p.getInventory().add(stack)) {
			p.drop(stack, false);
		}
		p.containerMenu.broadcastChanges();
	}

	// ------------------------------------------------------------------ HUD

	private static State state() {
		Mode m = mode();
		if (m == null) {
			return new State("", 0, 0, "", List.of(), List.of(), RunModifiers.hudLines(), false);
		}
		List<Line> obj = new ArrayList<>();
		String stageName = facts.victory ? "Complete!" : "";
		if (!facts.victory && facts.stage < m.stages.size()) {
			Stage st = m.stages.get(facts.stage);
			stageName = st.name;
			for (int i = 0; i < st.objectives.size(); i++) {
				Objective o = st.objectives.get(i);
				boolean done = facts.done.contains("s" + facts.stage + "o" + i);
				int t = target(o);
				obj.add(new Line(o.text, done, done ? t : Math.min(t, progress(o)), t));
			}
		}
		List<Line> sub = new ArrayList<>();
		for (int i = 0; i < m.subgoals.size(); i++) {
			Objective o = m.subgoals.get(i);
			boolean done = facts.done.contains("sub" + i);
			int t = target(o);
			sub.add(new Line(o.text, done, done ? t : Math.min(t, progress(o)), t));
		}
		return new State(m.name, Math.min(facts.stage + 1, m.stages.size()), m.stages.size(), stageName, obj, sub, RunModifiers.hudLines(), facts.victory);
	}

	static void broadcast() {
		if (server == null) {
			return;
		}
		State s = state();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(p, State.ID)) {
				ServerPlayNetworking.send(p, s);
			}
		}
	}

	private static void send(ServerPlayer p) {
		if (ServerPlayNetworking.canSend(p, State.ID)) {
			ServerPlayNetworking.send(p, state());
		}
	}

	// ------------------------------------------------------------------ files

	private static void loadFacts() {
		facts = new Facts();
		try {
			if (Files.exists(stateFile)) {
				Facts f = GSON.fromJson(Files.readString(stateFile), Facts.class);
				if (f != null) {
					facts = f;
				}
			} else {
				facts.mode = Pregame.gameMode();
				Bubble.LOG.info("Goals: this run's mode is {}", facts.mode);
			}
		} catch (Exception e) {
			Bubble.LOG.error("Could not read {}", stateFile, e);
		}
		if (facts.done == null) {
			facts.done = new HashSet<>();
		}
		if (facts.dimensions == null) {
			facts.dimensions = new HashSet<>();
		}
		if (facts.kills == null) {
			facts.kills = new HashMap<>();
		}
		if (facts.structures == null) {
			facts.structures = new HashSet<>();
		}
		if (facts.custom == null) {
			facts.custom = new HashMap<>();
		}
		facts.dimensions.add("minecraft:overworld");
		saveFacts();
	}

	private static void saveFacts() {
		if (stateFile == null) {
			return;
		}
		try {
			Files.writeString(stateFile, GSON.toJson(facts));
		} catch (Exception e) {
			Bubble.LOG.error("Could not save {}", stateFile, e);
		}
	}

	private static void loadModes() {
		Path file = FMLPaths.CONFIGDIR.get().resolve("bubble-goals.json");
		try {
			JsonObject root;
			JsonObject defaults = JsonParser.parseString(DEFAULTS).getAsJsonObject();
			if (Files.exists(file)) {
				root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
				// modes added in a newer version get added once
				JsonArray seen = root.has("defaultsSeen") ? root.getAsJsonArray("defaultsSeen") : new JsonArray();
				if (!root.has("defaultsSeen")) {
					root.getAsJsonObject("modes").keySet().forEach(seen::add);
					root.add("defaultsSeen", seen);
				}
				boolean changed = false;
				for (String id : defaults.getAsJsonObject("modes").keySet()) {
					if (!seen.contains(new com.google.gson.JsonPrimitive(id))) {
						seen.add(id);
						if (!root.getAsJsonObject("modes").has(id)) {
							root.getAsJsonObject("modes").add(id, defaults.getAsJsonObject("modes").get(id));
						}
						changed = true;
					}
				}
				JsonArray migrations = root.has("migrations") ? root.getAsJsonArray("migrations") : new JsonArray();
				if (!migrations.contains(new com.google.gson.JsonPrimitive("case_rewards_v1"))) {
					migrations.add("case_rewards_v1");
					root.add("migrations", migrations);
					addCaseRewards(root, defaults);
					changed = true;
				}
				if (changed) {
					Files.writeString(file, GSON.toJson(root));
				}
			} else {
				root = defaults;
				JsonArray seen = new JsonArray();
				root.getAsJsonObject("modes").keySet().forEach(seen::add);
				root.add("defaultsSeen", seen);
				JsonArray migrations = new JsonArray();
				migrations.add("case_rewards_v1");
				root.add("migrations", migrations);
				Files.createDirectories(file.getParent());
				Files.writeString(file, GSON.toJson(root));
			}
			configRoot = root;
			parseModes(root);
		} catch (Exception e) {
			Bubble.LOG.error("Could not read config/bubble-goals.json", e);
		}
	}

	private static void parseModes(JsonObject root) {
		try {
			modes.clear();
			for (Map.Entry<String, JsonElement> me : root.getAsJsonObject("modes").entrySet()) {
				JsonObject mo = me.getValue().getAsJsonObject();
				List<Stage> stages = new ArrayList<>();
				for (JsonElement se : mo.getAsJsonArray("stages")) {
					JsonObject so = se.getAsJsonObject();
					stages.add(new Stage(so.get("name").getAsString(), objectives(so.getAsJsonArray("objectives")),
							so.has("reward") ? so.get("reward").getAsInt() : 0, caseOf(so), casesOf(so)));
				}
				List<Objective> subs = mo.has("subgoals") ? objectives(mo.getAsJsonArray("subgoals")) : List.of();
				modes.put(me.getKey(), new Mode(me.getKey(), mo.get("name").getAsString(),
						mo.has("description") ? mo.get("description").getAsString() : "", stages, subs));
			}
		} catch (Exception e) {
			Bubble.LOG.error("Could not read config/bubble-goals.json", e);
		}
	}

	private static List<Objective> objectives(JsonArray arr) {
		List<Objective> out = new ArrayList<>();
		for (JsonElement e : arr) {
			JsonObject o = e.getAsJsonObject();
			List<String> ids = new ArrayList<>();
			for (String key : new String[]{"id", "ids"}) {
				if (o.has(key)) {
					JsonElement v = o.get(key);
					if (v.isJsonArray()) {
						v.getAsJsonArray().forEach(x -> ids.add(x.getAsString()));
					} else {
						ids.add(v.getAsString());
					}
				}
			}
			out.add(new Objective(o.get("type").getAsString(), o.get("text").getAsString(), ids,
					o.has("count") ? o.get("count").getAsInt() : 1, o.has("distinct") && o.get("distinct").getAsBoolean(),
					o.has("reward") ? o.get("reward").getAsInt() : 0, caseOf(o), casesOf(o)));
		}
		return out;
	}

	/** "case": "mod" (a case id from config/bubble-cases.json) and optional "cases": 2. */
	private static String caseOf(JsonObject o) {
		return o.has("case") ? o.get("case").getAsString() : "";
	}

	private static int casesOf(JsonObject o) {
		return !o.has("case") ? 0 : o.has("cases") ? Math.max(0, o.get("cases").getAsInt()) : 1;
	}

	/** One-time update for existing goal files: the built-in stages and bonus goals now also give cases. */
	private static void addCaseRewards(JsonObject root, JsonObject defaults) {
		JsonObject have = root.getAsJsonObject("modes");
		for (Map.Entry<String, JsonElement> me : defaults.getAsJsonObject("modes").entrySet()) {
			if (!have.has(me.getKey())) {
				continue;
			}
			JsonObject def = me.getValue().getAsJsonObject();
			JsonObject mine = have.getAsJsonObject(me.getKey());
			copyCases(mine.getAsJsonArray("stages"), def.getAsJsonArray("stages"), "name");
			if (mine.has("subgoals") && def.has("subgoals")) {
				copyCases(mine.getAsJsonArray("subgoals"), def.getAsJsonArray("subgoals"), "text");
			}
		}
	}

	private static void copyCases(JsonArray mine, JsonArray def, String key) {
		if (mine == null || def == null) {
			return;
		}
		for (JsonElement me : mine) {
			JsonObject m = me.getAsJsonObject();
			if (m.has("case") || !m.has(key)) {
				continue;
			}
			for (JsonElement de : def) {
				JsonObject d = de.getAsJsonObject();
				if (d.has("case") && d.has(key) && d.get(key).getAsString().equals(m.get(key).getAsString())) {
					m.add("case", d.get("case"));
					if (d.has("cases")) {
						m.add("cases", d.get("cases"));
					}
				}
			}
		}
	}

	private static final String DEFAULTS = """
			{
			  "modes": {
			    "full_run": {
			      "name": "The Full Run",
			      "description": "Everything: Overworld, Nether, Aether, Twilight Forest, Deep Dark, Cataclysm and the End.",
			      "stages": [
			        {"name": "Overworld: Gear Up", "reward": 100, "case": "mod", "objectives": [
			          {"type": "item", "id": "minecraft:iron_pickaxe", "text": "Make an iron pickaxe", "reward": 10},
			          {"type": "item", "id": "minecraft:diamond", "count": 3, "text": "Find 3 diamonds", "reward": 20},
			          {"type": "item", "id": "minecraft:obsidian", "count": 10, "text": "Collect 10 obsidian", "reward": 15}]},
			        {"name": "The Nether", "reward": 150, "case": "progression", "objectives": [
			          {"type": "dimension", "id": "minecraft:the_nether", "text": "Enter the Nether", "reward": 15},
			          {"type": "advancement", "id": "minecraft:nether/find_fortress", "text": "Find a Nether Fortress", "reward": 20},
			          {"type": "item", "id": "minecraft:blaze_rod", "count": 6, "text": "Collect 6 blaze rods", "reward": 25}]},
			        {"name": "The Aether", "reward": 200, "case": "mod", "cases": 2, "objectives": [
			          {"type": "dimension", "id": "aether:the_aether", "text": "Enter the Aether", "reward": 20},
			          {"type": "kill", "id": "aether:slider", "text": "Defeat the Slider", "reward": 40},
			          {"type": "kill", "id": "aether:valkyrie_queen", "text": "Defeat the Valkyrie Queen", "reward": 50}]},
			        {"name": "The Twilight Forest", "reward": 250, "case": "twilight", "objectives": [
			          {"type": "dimension", "id": "twilightforest:twilight_forest", "text": "Enter the Twilight Forest", "reward": 20},
			          {"type": "kill", "id": "twilightforest:naga", "text": "Defeat the Naga", "reward": 40},
			          {"type": "kill", "id": "twilightforest:lich", "text": "Defeat the Twilight Lich", "reward": 50},
			          {"type": "kill", "id": "twilightforest:hydra", "text": "Defeat the Hydra", "reward": 60}]},
			        {"name": "The Deep Dark", "reward": 200, "case": "weapons", "objectives": [
			          {"type": "structure", "id": "minecraft:ancient_city", "text": "Find an Ancient City", "reward": 30},
			          {"type": "item", "id": "minecraft:echo_shard", "count": 4, "text": "Collect 4 echo shards", "reward": 30}]},
			        {"name": "Cataclysm", "reward": 300, "case": "weapons", "cases": 2, "objectives": [
			          {"type": "kill", "ids": ["cataclysm:ender_guardian", "cataclysm:netherite_monstrosity", "cataclysm:ignis", "cataclysm:the_harbinger",
			            "cataclysm:the_leviathan", "cataclysm:ancient_remnant", "cataclysm:maledictus", "cataclysm:scylla"],
			            "count": 2, "distinct": true, "text": "Defeat 2 Cataclysm bosses", "reward": 80}]},
			        {"name": "The End", "reward": 500, "case": "mod", "cases": 3, "objectives": [
			          {"type": "item", "id": "minecraft:ender_eye", "count": 12, "text": "Make 12 eyes of ender", "reward": 30},
			          {"type": "dimension", "id": "minecraft:the_end", "text": "Enter the End", "reward": 40},
			          {"type": "kill", "id": "minecraft:ender_dragon", "text": "Slay the Ender Dragon", "reward": 100}]}
			      ],
			      "subgoals": [
			        {"type": "kill", "id": "minecraft:wither", "text": "Kill the Wither", "reward": 150, "case": "weapons"},
			        {"type": "kill", "id": "minecraft:warden", "text": "Kill a Warden", "reward": 150, "case": "weapons"},
			        {"type": "kill", "id": "bubble:watcher", "text": "Kill a Watcher (don't look!)", "reward": 50, "case": "mod"},
			        {"type": "advancement", "id": "minecraft:story/enchant_item", "text": "Enchant something", "reward": 15},
			        {"type": "advancement", "id": "minecraft:husbandry/tame_an_animal", "text": "Tame an animal", "reward": 15},
			        {"type": "advancement", "id": "apotheosis:progression/ascent", "text": "Reach world tier Ascent", "reward": 40},
			        {"type": "custom", "id": "lucky_block", "count": 5, "text": "Break 5 lucky blocks", "reward": 25},
			        {"type": "custom", "id": "case_opened", "text": "Open a case", "reward": 10}
			      ]
			    },
			    "dragon": {
			      "name": "Just the Dragon",
			      "description": "No objectives: kill the Ender Dragon.",
			      "stages": [
			        {"name": "Kill the Ender Dragon", "reward": 500, "case": "mod", "cases": 3, "objectives": [
			          {"type": "kill", "id": "minecraft:ender_dragon", "text": "Slay the Ender Dragon", "reward": 0}]}
			      ]
			    },
			    "boss_rush": {
			      "name": "Boss Rush",
			      "description": "Kill as many different bosses as you can.",
			      "stages": [
			        {"name": "Warm-up", "reward": 100, "case": "mod", "objectives": [
			          {"type": "kill", "ids": ["minecraft:wither", "minecraft:warden", "minecraft:elder_guardian", "twilightforest:naga", "aether:slider"],
			            "count": 2, "distinct": true, "text": "Defeat 2 different bosses", "reward": 40}]},
			        {"name": "Boss Hunter", "reward": 250, "case": "weapons", "cases": 2, "objectives": [
			          {"type": "kill", "ids": ["minecraft:wither", "minecraft:warden", "minecraft:elder_guardian", "minecraft:ender_dragon",
			            "twilightforest:naga", "twilightforest:lich", "twilightforest:minoshroom", "twilightforest:hydra", "twilightforest:knight_phantom",
			            "twilightforest:ur_ghast", "twilightforest:alpha_yeti", "twilightforest:snow_queen", "aether:slider", "aether:valkyrie_queen",
			            "aether:sun_spirit", "cataclysm:ender_guardian", "cataclysm:netherite_monstrosity", "cataclysm:ignis", "cataclysm:the_harbinger",
			            "cataclysm:the_leviathan", "cataclysm:ancient_remnant", "cataclysm:maledictus", "cataclysm:scylla"],
			            "count": 8, "distinct": true, "text": "Defeat 8 different bosses", "reward": 100}]}
			      ],
			      "subgoals": [
			        {"type": "kill", "id": "minecraft:ender_dragon", "text": "Slay the Ender Dragon", "reward": 150}
			      ]
			    },
			    "speedrun": {
			      "name": "Dragon Speedrun",
			      "description": "Classic: kill the Ender Dragon.",
			      "stages": [
			        {"name": "Nether", "reward": 100, "case": "progression", "objectives": [
			          {"type": "item", "id": "minecraft:blaze_rod", "count": 6, "text": "Collect 6 blaze rods", "reward": 20},
			          {"type": "item", "id": "minecraft:ender_pearl", "count": 12, "text": "Collect 12 ender pearls", "reward": 20}]},
			        {"name": "The End", "reward": 300, "objectives": [
			          {"type": "dimension", "id": "minecraft:the_end", "text": "Enter the End", "reward": 30},
			          {"type": "kill", "id": "minecraft:ender_dragon", "text": "Slay the Ender Dragon", "reward": 100}]}
			      ],
			      "subgoals": [
			        {"type": "custom", "id": "lucky_block", "count": 3, "text": "Break 3 lucky blocks", "reward": 20}
			      ]
			    }
			  }
			}""";
}
