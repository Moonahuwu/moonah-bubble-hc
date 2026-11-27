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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
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
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The wheels. The start-of-run wheel ("slices" in config/bubble-wheel.json) spins for everyone a few seconds after the
 * first player joins a brand-new world. The mid-run wheel ("midrun") spins whenever someone uses a Wheel Spin item
 * (found in cases and chests); everyone sees it.
 *
 * Slice actions ("who" is "all" (default), "spinner" (who used the Wheel Spin) or "random" (one random player)):
 *   {"type":"give", "item":"minecraft:diamond", "count":3}            everyone gets it (also players who join later)
 *        "charged": true                                              energy items come fully charged
 *   {"type":"give_random", "tag":"artifacts:artifacts", "count":1}     everyone gets one random item from a tag
 *   {"type":"give_one_of", "items":["a:b", "c:d"]}                     everyone gets one random item from the list
 *   {"type":"summon", "entity":"immersive_aircraft:warship"}           spawned once, next to the group
 *   {"type":"drop", "item":"minecraft:coal_block", "count":64}         dropped once, next to the group
 *   {"type":"command", "command":"time set day"}                       run once by the server
 *   {"type":"modifier", "id":"glass_cannon"}                           a run-long modifier (see RunModifiers)
 *   {"type":"effect", "effect":"minecraft:speed", "seconds":60, "amplifier":4}
 *   {"type":"fx", "kind":"upside_down"|"ice", "seconds":60}            done by the client (camera flip, slippery ground)
 *   {"type":"skydrop", "height":100}                                   teleported up with a water bucket in hand
 *   {"type":"matter", "amount":150}                                    Strange Matter
 *   {"type":"status", "kind":"meteor_rain", "seconds":30}              a timed status with a countdown bar (see WheelStatus)
 *   {"type":"shuffle_hotbar"} / {"type":"swap_hotbars"}                mix up hotbars / pass them round the group
 *   {"type":"heal"}                                                    full health and food
 *   {"type":"teleport", "radius":50}                                   somewhere safe nearby
 *   {"type":"ambush", "mobs":["minecraft:zombie"], "count":6, "radius":7}  a ring of mobs round each player
 *   {"type":"loot", "tables":["minecraft:chests/simple_dungeon"], "rolls":2}  a burst of loot round each player
 *   {"type":"ammo", "magazines":5}                                     ammo for every gun each player carries (TACZ)
 *   {"type":"boss", "bosses":["cataclysm:ignis"], "warning":10}        a boss turns up near one of you after a warning
 *   {"type":"cases", "count":5}                                        that many random cases each
 *   {"type":"yeet", "speed":30}                                        launched as a ragdoll (use "who":"random" for one player)
 *   {"type":"ragdoll", "seconds":10}                                   tossed about as ragdolls (Sable Ragdolls; off the wheel without it)
 *   summon also takes "count", "radius", "leave_after" (seconds, then it vanishes) and "spawn_type" ("triggered")
 */
public final class Wheel {
	static final int SPIN_TICKS = 150;
	private static final int DELAY_TICKS = 200;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** enabled = false: kept in the file (and the admin editor) but left off the wheel. */
	record Slice(String name, int color, int weight, boolean enabled, List<JsonObject> actions) {
	}

	/** The slices that actually go on the wheel. */
	private static List<Slice> pool(List<Slice> all) {
		return all.stream().filter(s -> s.enabled() && s.weight() > 0 && (Ragdolls.available() || !needsRagdolls(s))).toList();
	}

	private static boolean needsRagdolls(Slice s) {
		return s.actions().stream().anyMatch(a -> a.has("type") && ("ragdoll".equals(a.get("type").getAsString()) || "yeet".equals(a.get("type").getAsString())
				|| ("status".equals(a.get("type").getAsString()) && a.has("kind")
						&& Set.of("fragile", "ragdoll_roulette").contains(a.get("kind").getAsString()))));
	}

	/** Server to client: spin the wheel, landing on `result` after `ticks`. */
	public record Spin(String title, List<String> names, List<Integer> colors, int result, int ticks) implements CustomPacketPayload {
		public static final Type<Spin> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "wheel_spin"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Spin> CODEC = StreamCodec.ofMember((s, buf) -> {
			buf.writeUtf(s.title, 64);
			buf.writeVarInt(s.names.size());
			for (int i = 0; i < s.names.size(); i++) {
				buf.writeUtf(s.names.get(i), 64);
				buf.writeInt(s.colors.get(i));
			}
			buf.writeVarInt(s.result);
			buf.writeVarInt(s.ticks);
		}, buf -> {
			String title = buf.readUtf(64);
			int n = Math.min(buf.readVarInt(), 128);
			List<String> names = new ArrayList<>();
			List<Integer> colors = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				names.add(buf.readUtf(64));
				colors.add(buf.readInt());
			}
			return new Spin(title, names, colors, buf.readVarInt(), buf.readVarInt());
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	private static List<Slice> slices = new ArrayList<>();
	private static List<Slice> midSlices = new ArrayList<>();
	private static long midApplyAt = -1;
	private static int midResult = -1;
	private static java.util.UUID midSpinner;
	/** Client effects ("fx") that are on for everyone, and the server tick they end at, so people who join get them too. */
	private static final java.util.Map<String, Long> fxUntil = new java.util.HashMap<>();
	/** Potion effects wheel slices have handed out this session, so /bubble wheel clear can take them off again. */
	private static final Set<String> givenEffects = new java.util.LinkedHashSet<>();
	private static MinecraftServer server;
	private static Path stateFile;
	/** The world needs a spin (a fresh world); saved so a restart before anyone joins doesn't lose it. */
	private static boolean pending;
	private static int result = -1;
	/** The start slice the wheel landed on, by name (the index shifts when slices are moved between wheels). */
	private static String resultName;
	private static final Set<String> rewarded = new HashSet<>();
	private static long spinAt = -1;
	private static long applyAt = -1;

	private Wheel() {
	}

	static void register(PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c) {
		s2c.register(Spin.ID, Spin.CODEC);
		s2c.register(Fx.Start.ID, Fx.Start.CODEC);
	}

	static void init() {
		load();
		var bus = NeoForge.EVENT_BUS;
		bus.addListener((ServerStartedEvent e) -> {
			server = e.getServer();
			stateFile = server.getWorldPath(LevelResource.ROOT).resolve("bubble-wheel-state.json");
			spinAt = applyAt = midApplyAt = -1;
			fxUntil.clear();
			readState();
			// A world that has only just been made gets a spin (an old world from before the wheel existed doesn't).
			if (!Files.exists(stateFile) && server.overworld().getGameTime() < 1200 && Pregame.wheelEnabled()) {
				pending = true;
				saveState();
				Bubble.LOG.info("Start-of-run wheel: spins once someone joins");
			}
		});
		bus.addListener((ServerStoppedEvent e) -> server = null);
		bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
			if (!(e.getEntity() instanceof ServerPlayer p)) {
				return;
			}
			long now = p.server.getTickCount();
			fxUntil.forEach((kind, until) -> {
				if (until > now) {
					sendFx(p, kind, (int) (until - now));
				}
			});
			if (pending && !Lobby.active() && spinAt < 0 && applyAt < 0) {
				spinAt = p.server.getTickCount() + DELAY_TICKS;
			} else if (result >= 0 && applyAt < 0 && !rewarded.contains(p.getStringUUID())) {
				p.server.execute(() -> rewardLate(p));
			}
		});
		bus.addListener((ServerTickEvent.Post e) -> {
			long t = e.getServer().getTickCount();
			if (spinAt >= 0 && t >= spinAt) {
				spinAt = -1;
				spin();
			}
			if (applyAt >= 0 && t >= applyAt) {
				applyAt = -1;
				apply();
			}
			if (midApplyAt >= 0 && t >= midApplyAt) {
				midApplyAt = -1;
				applyMid();
			}
			if (t % 20 == 0) {
				WheelStatus.sendOffLeavers(e.getServer());
			}
		});
	}

	/** The start wheel still has to spin in this world (for the ready-up screen's info line). */
	static boolean spinPending() {
		return pending;
	}

	/** The lobby finished: spin now if this world still owes a spin. */
	static void roundStarted() {
		if (pending && applyAt < 0 && server != null) {
			spinAt = server.getTickCount() + 40;
		}
	}

	/** Admin tab: spin right away. */
	static boolean spinNow() {
		if (applyAt >= 0 || server == null) {
			return false;
		}
		spinAt = -1;
		spin();
		return true;
	}

	static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> command() {
		return net.minecraft.commands.Commands.literal("wheel").requires(s -> s.hasPermission(2))
				.executes(c -> {
					if (applyAt >= 0) {
						c.getSource().sendFailure(Component.literal("The wheel is already spinning."));
						return 0;
					}
					spinAt = -1;
					spin();
					return 1;
				})
				.then(net.minecraft.commands.Commands.literal("reload").executes(c -> {
					load();
					c.getSource().sendSuccess(() -> Component.literal("Wheel reloaded: " + slices.size() + " start slices, "
							+ midSlices.size() + " mid-run slices"), false);
					return 1;
				}))
				.then(net.minecraft.commands.Commands.literal("clear")
						.executes(c -> clear(c.getSource(), null))
						.then(net.minecraft.commands.Commands.argument("status", com.mojang.brigadier.arguments.StringArgumentType.word())
								.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(statuses(), b))
								.executes(c -> clear(c.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "status")))))
				.then(net.minecraft.commands.Commands.literal("apply")
						.then(net.minecraft.commands.Commands.argument("slice", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
								.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
										java.util.stream.Stream.concat(midSlices.stream(), slices.stream()).map(Slice::name), b))
								.executes(c -> applyNamed(c.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "slice")))))
				.then(net.minecraft.commands.Commands.literal("move")
						.then(net.minecraft.commands.Commands.argument("slice", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
								.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
										java.util.stream.Stream.concat(slices.stream(), midSlices.stream()).map(Slice::name), b))
								.executes(c -> moveNamed(c.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "slice")))))
				.then(net.minecraft.commands.Commands.literal("midrun").executes(c -> {
					String why = spinMidRun(c.getSource().getPlayer());
					if (why != null) {
						c.getSource().sendFailure(Component.literal(why));
						return 0;
					}
					return 1;
				}));
	}

	// ------------------------------------------------------------------ spinning

	/** What /bubble wheel clear can take off: run modifiers, client effects (upside_down, ice) and potion effects. */
	private static List<String> statuses() {
		List<String> out = new ArrayList<>(RunModifiers.activeIds());
		long now = server == null ? 0 : server.getTickCount();
		fxUntil.forEach((k, until) -> {
			if (until > now) {
				out.add(k);
			}
		});
		for (String k : WheelStatus.active()) {
			if (!out.contains(k)) {
				out.add(k);
			}
		}
		if (!givenEffects.isEmpty()) {
			out.add("effects");
		}
		return out;
	}

	/** Takes wheel statuses off everyone: one by name, or all of them (null). */
	private static int clear(CommandSourceStack src, String status) {
		if (server == null) {
			return 0;
		}
		List<String> done = new ArrayList<>();
		boolean all = status == null;
		if (all || RunModifiers.activeIds().contains(status)) {
			int n = RunModifiers.deactivate(all ? null : status);
			if (n > 0) {
				done.add(all ? n + " run modifier(s)" : status);
			}
		}
		for (String kind : new ArrayList<>(fxUntil.keySet())) {
			if (all || kind.equals(status)) {
				fxUntil.remove(kind);
				done.add(kind);
			}
		}
		for (String kind : WheelStatus.active()) {
			if ((all || kind.equals(status)) && WheelStatus.stop(kind) && !done.contains(kind)) {
				done.add(kind);
			}
		}
		if (all || "upside_down".equals(status) || "ice".equals(status)) {
			// also catches one aimed at a single player (not tracked in fxUntil)
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (all || "upside_down".equals(status)) {
					sendFx(p, "upside_down", 0);
				}
				if (all || "ice".equals(status)) {
					sendFx(p, "ice", 0);
				}
			}
		}
		if (all || "effects".equals(status)) {
			int removed = 0;
			for (String id : givenEffects) {
				var effect = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse(id));
				if (effect.isPresent()) {
					for (ServerPlayer p : server.getPlayerList().getPlayers()) {
						if (p.removeEffect(effect.get())) {
							removed++;
						}
					}
				}
			}
			givenEffects.clear();
			if (removed > 0) {
				done.add("potion effects");
			}
		}
		if (done.isEmpty()) {
			src.sendFailure(Component.literal(all ? "No wheel statuses are on." : "'" + status + "' isn't on. On now: " + String.join(", ", statuses())));
			return 0;
		}
		server.getPlayerList().broadcastSystemMessage(Component.literal("🎡 " + src.getTextName() + " cleared the wheel's " + String.join(", ", done) + ".")
				.withStyle(ChatFormatting.GRAY), false);
		return done.size();
	}

	// ------------------------------------------------------------------ admin editor (Bubble menu > Admin > Wheel)

	/** For the admin screen: both wheels' slices as JSON. */
	static JsonObject editorJson() {
		JsonObject o = new JsonObject();
		o.add("start", sliceJson(slices));
		o.add("mid", sliceJson(midSlices));
		return o;
	}

	private static JsonArray sliceJson(List<Slice> list) {
		JsonArray arr = new JsonArray();
		boolean mid = list == midSlices;
		for (Slice s : list) {
			JsonObject so = new JsonObject();
			so.addProperty("name", s.name());
			so.addProperty("color", s.color());
			so.addProperty("weight", s.weight());
			so.addProperty("enabled", s.enabled());
			so.addProperty("warn", warning(s, mid));
			so.addProperty("moveWarn", warning(s, !mid));
			arr.add(so);
		}
		return arr;
	}

	/** What works differently about a slice on that wheel (a warning in the admin screen), or "" when nothing does. */
	private static String warning(Slice s, boolean onMid) {
		Set<String> w = new java.util.LinkedHashSet<>();
		for (JsonObject a : s.actions()) {
			String type = a.has("type") ? a.get("type").getAsString() : "";
			if (onMid && type.equals("modifier")) {
				w.add(switch (a.get("id").getAsString()) {
					case "sky_island" -> "Sends everyone to the Aether the moment it lands, wherever they are and whatever they're doing.";
					case "pacifist" -> "No fighting for 10 minutes from when it lands (mid-fight too), then Strength for the rest of the run.";
					case "speedrun" -> "The 30 minutes to kill a boss start when it lands.";
					case "hard_world" -> "Puts the world tier up to Ascent the moment it lands.";
					default -> "A run rule: from when it lands it stays on for the rest of the run.";
				});
				w.add("Does nothing if that rule is already on.");
			}
			if (type.equals("ammo")) {
				w.add("Only gives ammo to players carrying a gun.");
			}
			if (type.equals("yeet")) {
				w.add("Launches " + ("random".equals(a.has("who") ? a.get("who").getAsString() : "") ? "one random player" : "everyone")
						+ " high into the air as a ragdoll. The landing can hurt or kill.");
			}
			if (type.equals("ragdoll")) {
				w.add("Everyone goes limp and gets thrown about for " + (a.has("seconds") ? a.get("seconds").getAsInt() : 10)
						+ " seconds; hard landings hurt. Needs Sable Ragdolls (left off the wheel without it).");
			}
			if (type.equals("boss")) {
				w.add("A Cataclysm boss turns up near one random player after a " + (a.has("warning") ? a.get("warning").getAsInt() : 10)
						+ " second warning. Early in a run this can end it.");
			}
			if (!onMid && type.equals("status")) {
				int secs = a.has("seconds") ? a.get("seconds").getAsInt() : 60;
				w.add("Only lasts " + (secs % 60 == 0 ? secs / 60 + " min" : secs + " s") + " at the start of the run, not the whole run.");
			}
		}
		return String.join(" ", w);
	}

	/** The start slice this world's wheel landed on (looked up by name, so it survives slices being moved). */
	private static Slice landed() {
		if (resultName != null) {
			for (Slice s : slices) {
				if (s.name().equals(resultName)) {
					return s;
				}
			}
			for (Slice s : midSlices) {
				if (s.name().equals(resultName)) {
					return s;
				}
			}
		}
		return result >= 0 && result < slices.size() ? slices.get(result) : null;
	}

	/** Admin screen "Move": takes a slice off one wheel and puts it at the end of the other. Returns why not, or null. */
	static String moveSlice(boolean fromMid, int index) {
		if (spinAt >= 0 || applyAt >= 0 || midApplyAt >= 0) {
			return "Wait for the wheel to stop spinning first.";
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
			if (!root.has("midrun")) {
				root.add("midrun", new JsonArray());
			}
			JsonArray from = root.getAsJsonArray(fromMid ? "midrun" : "slices");
			JsonArray to = root.getAsJsonArray(fromMid ? "slices" : "midrun");
			if (index < 0 || index >= from.size()) {
				return "That slice isn't there any more.";
			}
			if (to.size() >= 64) {
				return "The other wheel is full (64 slices).";
			}
			if (result >= 0 && resultName == null && result < slices.size()) {
				resultName = slices.get(result).name();
				saveState();
			}
			JsonElement moved = from.remove(index);
			to.add(moved);
			Files.writeString(file(), GSON.toJson(root));
			load();
			if (resultName != null) {
				result = slices.stream().map(Slice::name).toList().indexOf(resultName);
				saveState();
			}
			Bubble.LOG.info("Wheel: moved '{}' to the {} wheel", moved.getAsJsonObject().get("name").getAsString(), fromMid ? "start" : "mid-run");
			return null;
		} catch (Exception e) {
			Bubble.LOG.error("Could not edit config/bubble-wheel.json", e);
			return "Couldn't save the wheel file.";
		}
	}

	/** Switches a slice on/off or changes its weight in config/bubble-wheel.json, then reloads. */
	static boolean editSlice(boolean mid, int index, Boolean enabled, Integer weight) {
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
			JsonArray arr = root.getAsJsonArray(mid ? "midrun" : "slices");
			if (arr == null || index < 0 || index >= arr.size()) {
				return false;
			}
			JsonObject so = arr.get(index).getAsJsonObject();
			if (enabled != null) {
				so.addProperty("enabled", enabled);
			}
			if (weight != null) {
				so.addProperty("weight", Math.max(0, Math.min(99, weight)));
			}
			Files.writeString(file(), GSON.toJson(root));
			load();
			return true;
		} catch (Exception e) {
			Bubble.LOG.error("Could not edit config/bubble-wheel.json", e);
			return false;
		}
	}

	/** Admin screen "Do it": that slice, right now. */
	static boolean applyIndex(boolean mid, int index, ServerPlayer by) {
		List<Slice> list = mid ? midSlices : slices;
		if (server == null || index < 0 || index >= list.size()) {
			return false;
		}
		announce(list.get(index));
		RunLog.add(RunLog.ADMIN, (by == null ? "An op" : by.getGameProfile().getName()) + " did the " + list.get(index).name() + " slice");
		run(list.get(index), server.getPlayerList().getPlayers(), by);
		return true;
	}

	/** A slice's name for the admin pop-ups, or "" if there isn't one there. */
	static String sliceName(boolean mid, int index) {
		List<Slice> list = mid ? midSlices : slices;
		return index >= 0 && index < list.size() ? list.get(index).name() : "";
	}

	/** /bubble wheel apply <slice>: does that slice right now, no spin (mid-run slices first, then start ones). */
	private static int applyNamed(CommandSourceStack src, String name) {
		if (server == null) {
			return 0;
		}
		Slice slice = java.util.stream.Stream.concat(midSlices.stream(), slices.stream())
				.filter(x -> x.name().equalsIgnoreCase(name.trim())).findFirst().orElse(null);
		if (slice == null) {
			src.sendFailure(Component.literal("No slice called '" + name + "'."));
			return 0;
		}
		announce(slice);
		RunLog.add(RunLog.ADMIN, (src.getPlayer() == null ? "The server" : src.getPlayer().getGameProfile().getName()) + " did the " + slice.name() + " slice");
		run(slice, server.getPlayerList().getPlayers(), src.getPlayer());
		return 1;
	}

	/** /bubble wheel move <slice>: moves it to the other wheel (start slices are looked for first). */
	private static int moveNamed(CommandSourceStack src, String name) {
		List<String> start = slices.stream().map(s -> s.name().toLowerCase()).toList();
		List<String> mid = midSlices.stream().map(s -> s.name().toLowerCase()).toList();
		String n = name.trim().toLowerCase();
		boolean fromMid = !start.contains(n);
		int index = fromMid ? mid.indexOf(n) : start.indexOf(n);
		if (index < 0) {
			src.sendFailure(Component.literal("No slice called '" + name + "'."));
			return 0;
		}
		Slice s = (fromMid ? midSlices : slices).get(index);
		String why = moveSlice(fromMid, index);
		if (why != null) {
			src.sendFailure(Component.literal(why));
			return 0;
		}
		String warn = warning(s, !fromMid);
		src.sendSuccess(() -> Component.literal("Moved '" + s.name() + "' to the " + (fromMid ? "start" : "mid-run") + " wheel."
				+ (warn.isEmpty() ? "" : " Note: " + warn)), true);
		if (server != null) {
			WheelAdmin.broadcast(server);
		}
		return 1;
	}

	private static int pick(List<Slice> from) {
		int total = from.stream().mapToInt(Slice::weight).sum();
		int r = server.overworld().random.nextInt(Math.max(1, total));
		for (int i = 0; i < from.size(); i++) {
			r -= from.get(i).weight();
			if (r < 0) {
				return i;
			}
		}
		return 0;
	}

	private static void sendSpin(Spin spin, String fallback) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(p, Spin.ID)) {
				ServerPlayNetworking.send(p, spin);
			} else {
				p.sendSystemMessage(Component.literal(fallback).withStyle(ChatFormatting.GOLD));
			}
		}
	}

	/** A Wheel Spin was used: spin the mid-run wheel. Returns why it can't (the item isn't used up then), or null. */
	static String spinMidRun(ServerPlayer p) {
		if (server == null || midSlices.isEmpty()) {
			return "The mid-run wheel has no slices.";
		}
		if (Lobby.active()) {
			return "Wait for the run to start.";
		}
		if (applyAt >= 0 || midApplyAt >= 0) {
			return "A wheel is already spinning.";
		}
		List<Slice> on = pool(midSlices);
		if (on.isEmpty()) {
			return "Every mid-run slice is switched off.";
		}
		int landed = pick(on);
		midResult = midSlices.indexOf(on.get(landed));
		midSpinner = p == null ? null : p.getUUID();
		midApplyAt = server.getTickCount() + SPIN_TICKS;
		String who = p == null ? "The server" : p.getGameProfile().getName();
		sendSpin(new Spin(who + "'s Wheel Spin", on.stream().map(Slice::name).toList(), on.stream().map(Slice::color).toList(),
				landed, SPIN_TICKS), "🎡 " + who + " is spinning the mid-run wheel...");
		Bubble.LOG.info("Mid-run wheel: {} spun, will land on '{}'", who, midSlices.get(midResult).name());
		return null;
	}

	private static void applyMid() {
		if (server == null || midResult < 0 || midResult >= midSlices.size()) {
			return;
		}
		Slice slice = midSlices.get(midResult);
		ServerPlayer spinner = midSpinner == null ? null : server.getPlayerList().getPlayer(midSpinner);
		announce(slice);
		RunLog.add(RunLog.SPIN, (spinner == null ? "A Wheel Spin" : spinner.getGameProfile().getName() + "'s Wheel Spin") + " landed on " + slice.name());
		run(slice, server.getPlayerList().getPlayers(), spinner);
	}

	private static void spin() {
		if (server == null || slices.isEmpty()) {
			return;
		}
		List<Slice> on = pool(slices);
		if (on.isEmpty()) {
			Bubble.LOG.warn("Start-of-run wheel: every slice is switched off, no spin");
			pending = false;
			saveState();
			return;
		}
		int landed = pick(on);
		int pick = slices.indexOf(on.get(landed));
		result = pick;
		resultName = slices.get(pick).name();
		pending = false;
		rewarded.clear();
		applyAt = server.getTickCount() + SPIN_TICKS;
		saveState();
		sendSpin(new Spin("Start-of-run wheel", on.stream().map(Slice::name).toList(), on.stream().map(Slice::color).toList(), landed, SPIN_TICKS),
				"🎡 The start-of-run wheel is spinning...");
		Bubble.LOG.info("Start-of-run wheel: spinning, will land on '{}'", slices.get(pick).name());
	}

	private static void apply() {
		Slice slice = landed();
		if (server == null || slice == null) {
			return;
		}
		announce(slice);
		RunLog.add(RunLog.WHEEL, "The start wheel landed on " + slice.name());
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		run(slice, players, null);
		players.forEach(p -> rewarded.add(p.getStringUUID()));
		saveState();
	}

	private static void announce(Slice slice) {
		server.getPlayerList().broadcastSystemMessage(Component.literal("🎡 The wheel landed on ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(slice.name()).withStyle(s -> s.withColor(slice.color()).withBold(true)))
				.append(Component.literal("!").withStyle(ChatFormatting.GOLD)), false);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			p.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.2f);
		}
	}

	/** Who an action is for: everyone, the spinner, or one random player. */
	private static List<ServerPlayer> targets(JsonObject a, List<ServerPlayer> players, ServerPlayer spinner) {
		String who = a.has("who") ? a.get("who").getAsString() : "all";
		List<ServerPlayer> playing = players.stream().filter(BubbleServer::counts).toList();
		List<ServerPlayer> pool = playing.isEmpty() ? players : playing;
		return switch (who) {
			case "spinner" -> spinner != null ? List.of(spinner) : List.of();
			case "random" -> pool.isEmpty() ? List.of() : List.of(pool.get(server.overworld().random.nextInt(pool.size())));
			default -> players;
		};
	}

	private static void run(Slice slice, List<ServerPlayer> players, ServerPlayer spinner) {
		ServerPlayer anchor = players.stream().filter(BubbleServer::counts).findFirst().orElse(players.isEmpty() ? null : players.get(0));
		for (JsonObject a : slice.actions()) {
			String type = a.has("type") ? a.get("type").getAsString() : "";
			try {
				List<ServerPlayer> who = targets(a, players, spinner);
				switch (type) {
					case "give", "give_random", "give_one_of" -> who.forEach(p -> givePlayer(p, a));
					case "summon" -> summon(anchor, a);
					case "drop" -> drop(anchor, a);
					case "command" -> runCommand(anchor, a.get("command").getAsString());
					case "modifier" -> RunModifiers.activate(a.get("id").getAsString());
					case "effect" -> who.forEach(p -> effect(p, a));
					case "fx" -> fx(who, a, who == players);
					case "skydrop" -> who.forEach(p -> skydrop(p, a));
					case "matter" -> who.forEach(p -> Matter.earn(p, a.get("amount").getAsDouble()));
					case "status" -> WheelStatus.start(a.get("kind").getAsString(), (a.has("seconds") ? a.get("seconds").getAsInt() : 60) * 20);
					case "shuffle_hotbar" -> who.stream().filter(BubbleServer::counts).forEach(WheelStatus::shuffleHotbar);
					case "swap_hotbars" -> WheelStatus.swapHotbars(who);
					case "heal" -> who.forEach(WheelStatus::heal);
					case "teleport" -> who.forEach(p -> WheelStatus.teleport(p, a.has("radius") ? a.get("radius").getAsInt() : 50));
					case "ambush" -> who.stream().filter(BubbleServer::counts).forEach(p -> ambush(p, a));
					case "loot" -> who.stream().filter(BubbleServer::counts).forEach(p -> loot(p, a));
					case "ammo" -> who.forEach(p -> ammo(p, a));
					case "gun", "apoth_loot" -> who.forEach(p -> givePlayer(p, a));
					case "boss" -> Bosses.wheelBoss(players, a);
					case "cases" -> who.stream().filter(BubbleServer::counts).forEach(p -> cases(p, a.has("count") ? a.get("count").getAsInt() : 5));
					case "yeet" -> who.stream().filter(BubbleServer::counts)
							.forEach(p -> Ragdolls.yeet(p, a.has("speed") ? a.get("speed").getAsDouble() : 30));
					case "ragdoll" -> who.stream().filter(BubbleServer::counts)
							.forEach(p -> Ragdolls.toss(p, (a.has("seconds") ? a.get("seconds").getAsInt() : 10) * 20));
					default -> Bubble.LOG.warn("Wheel: unknown action type '{}' in slice '{}'", type, slice.name());
				}
			} catch (Exception ex) {
				Bubble.LOG.error("Wheel: action {} in slice '{}' failed", a, slice.name(), ex);
			}
		}
	}

	/** `count` random cases (each one picked on its own). */
	private static void cases(ServerPlayer p, int count) {
		List<String> ids = new ArrayList<>(Gamble.ids());
		if (ids.isEmpty()) {
			return;
		}
		for (int i = 0; i < Math.max(1, Math.min(64, count)); i++) {
			p.getInventory().placeItemBackInInventory(CaseItem.of(ids.get(p.getRandom().nextInt(ids.size())), 1));
		}
		p.containerMenu.broadcastChanges();
	}

	private static void effect(ServerPlayer p, JsonObject a) {
		var effect = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse(a.get("effect").getAsString()));
		if (effect.isEmpty()) {
			Bubble.LOG.warn("Wheel: no effect {}", a.get("effect").getAsString());
			return;
		}
		int ticks = (a.has("seconds") ? a.get("seconds").getAsInt() : 30) * 20;
		int amp = a.has("amplifier") ? a.get("amplifier").getAsInt() : 0;
		p.addEffect(new net.minecraft.world.effect.MobEffectInstance(effect.get(), ticks, amp));
		givenEffects.add(a.get("effect").getAsString());
	}

	private static void sendFx(ServerPlayer p, String kind, int ticks) {
		if (ServerPlayNetworking.canSend(p, Fx.Start.ID)) {
			ServerPlayNetworking.send(p, new Fx.Start(kind, ticks));
		}
	}

	private static void fx(List<ServerPlayer> who, JsonObject a, boolean everyone) {
		String kind = a.get("kind").getAsString();
		int ticks = (a.has("seconds") ? a.get("seconds").getAsInt() : 60) * 20;
		who.forEach(p -> sendFx(p, kind, ticks));
		if (everyone) {
			fxUntil.put(kind, server.getTickCount() + (long) ticks);
			WheelStatus.start(kind, ticks); // countdown bar
		}
	}

	/** Up you go, with a water bucket in your hand. Not in the Nether (you'd end up on the roof). */
	private static void skydrop(ServerPlayer p, JsonObject a) {
		ServerLevel level = p.serverLevel();
		if (level.dimensionType().hasCeiling()) {
			p.sendSystemMessage(Component.literal("🎡 The roof saved you from the sky drop this time.").withStyle(ChatFormatting.GRAY));
			return;
		}
		int height = a.has("height") ? a.get("height").getAsInt() : 100;
		double y = Math.min(p.getY() + height, level.getMaxBuildHeight() - 2);
		var inv = p.getInventory();
		ItemStack held = inv.getSelected().copy();
		inv.setItem(inv.selected, new ItemStack(net.minecraft.world.item.Items.WATER_BUCKET));
		if (!held.isEmpty() && !inv.add(held)) {
			p.drop(held, false);
		}
		p.stopRiding();
		p.teleportTo(level, p.getX(), y, p.getZ(), p.getYRot(), 60);
		p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
		p.resetFallDistance();
		p.containerMenu.broadcastChanges();
		BubbleServer.title(p, Component.literal("MLG!").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
				Component.literal("Save yourself with the water bucket").withStyle(ChatFormatting.GRAY), 0, 50, 10);
	}

	/** Someone who wasn't online for the spin gets the per-player part of the result. */
	private static void rewardLate(ServerPlayer p) {
		Slice slice = landed();
		if (slice == null) {
			return;
		}
		boolean any = false;
		for (JsonObject a : slice.actions()) {
			String type = a.has("type") ? a.get("type").getAsString() : "";
			if (type.equals("give") || type.equals("give_random") || type.equals("give_one_of") || type.equals("gun") || type.equals("apoth_loot")) {
				givePlayer(p, a);
				any = true;
			}
		}
		rewarded.add(p.getStringUUID());
		saveState();
		if (any) {
			p.sendSystemMessage(Component.literal("🎡 This run's wheel landed on " + slice.name() + ", here's your share.").withStyle(ChatFormatting.GOLD));
		}
	}

	private static void givePlayer(ServerPlayer p, JsonObject a) {
		String kind = a.has("type") ? a.get("type").getAsString() : "";
		if (kind.equals("gun")) {
			if (!Guns.available()) {
				return;
			}
			List<String> ids = new ArrayList<>();
			if (a.has("guns")) {
				a.getAsJsonArray("guns").forEach(x -> ids.add(x.getAsString()));
			}
			var gun = ids.isEmpty() ? Optional.<Guns.Gun>empty() : Guns.find(ids.get(p.getRandom().nextInt(ids.size())));
			Guns.give(p, gun.orElse(Guns.GUNS.get(p.getRandom().nextInt(Guns.GUNS.size()))));
			p.containerMenu.broadcastChanges();
			return;
		}
		if (kind.equals("apoth_loot")) {
			if (net.neoforged.fml.ModList.get().isLoaded("apotheosis")) {
				ApothRoll.giveRandom(p, a.has("min_rarity") ? a.get("min_rarity").getAsInt() : 3);
				p.containerMenu.broadcastChanges();
			}
			return;
		}
		int count = a.has("count") ? a.get("count").getAsInt() : 1;
		ItemStack stack;
		if (a.has("items")) {
			List<Item> options = new ArrayList<>();
			a.getAsJsonArray("items").forEach(x -> BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(x.getAsString())).ifPresent(options::add));
			if (options.isEmpty()) {
				return;
			}
			stack = new ItemStack(options.get(p.getRandom().nextInt(options.size())), count);
		} else if (a.has("tag")) {
			TagKey<Item> tag = TagKey.create(Registries.ITEM, ResourceLocation.parse(a.get("tag").getAsString()));
			Optional<Holder<Item>> pick = BuiltInRegistries.ITEM.getTag(tag).flatMap(set -> set.getRandomElement(p.getRandom()));
			if (pick.isEmpty()) {
				return;
			}
			stack = new ItemStack(pick.get(), count);
		} else {
			Optional<Item> item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(a.get("item").getAsString()));
			if (item.isEmpty()) {
				Bubble.LOG.warn("Wheel: no item {}", a.get("item").getAsString());
				return;
			}
			stack = new ItemStack(item.get(), count);
		}
		if (a.has("charged") && a.get("charged").getAsBoolean()) {
			charge(stack);
		}
		p.sendSystemMessage(Component.literal("  + ").withStyle(ChatFormatting.GRAY).append(stack.getHoverName())
				.append(Component.literal(count > 1 ? " ×" + count : "").withStyle(ChatFormatting.GRAY)));
		if (!p.getInventory().add(stack)) {
			p.drop(stack, false);
		}
		p.containerMenu.broadcastChanges();
	}

	/** Fills an energy item (Oritech's railgun etc.) to the top. */
	static void charge(ItemStack stack) {
		var energy = stack.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM);
		if (energy == null) {
			Bubble.LOG.warn("Wheel: {} has no energy storage to charge", stack.getItem());
			return;
		}
		for (int i = 0; i < 10000 && energy.getEnergyStored() < energy.getMaxEnergyStored(); i++) {
			if (energy.receiveEnergy(Integer.MAX_VALUE, false) <= 0) {
				break;
			}
		}
	}

	/** A clear spot on the ground a few blocks in front of the anchor player. */
	private static Vec3 spot(ServerPlayer anchor, double ahead) {
		ServerLevel level = anchor.serverLevel();
		Vec3 look = anchor.getLookAngle().multiply(1, 0, 1).normalize();
		if (look.lengthSqr() < 1e-4) {
			look = new Vec3(1, 0, 0);
		}
		int x = (int) Math.floor(anchor.getX() + look.x * ahead);
		int z = (int) Math.floor(anchor.getZ() + look.z * ahead);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		return new Vec3(x + 0.5, y, z + 0.5);
	}

	private static void summon(ServerPlayer anchor, JsonObject a) {
		if (anchor == null) {
			return;
		}
		Optional<EntityType<?>> type = EntityType.byString(a.get("entity").getAsString());
		if (type.isEmpty()) {
			Bubble.LOG.warn("Wheel: no entity {}", a.get("entity").getAsString());
			return;
		}
		int count = a.has("count") ? a.get("count").getAsInt() : 1;
		if (!a.has("radius")) {
			Entity e = type.get().create(anchor.serverLevel());
			if (e == null) {
				return;
			}
			Vec3 at = spot(anchor, 10);
			e.moveTo(at.x, at.y + 0.5, at.z, anchor.getYRot(), 0);
			anchor.serverLevel().addFreshEntity(e);
			if (a.has("leave_after")) {
				WheelStatus.markLeaving(e, a.get("leave_after").getAsInt());
			}
			return;
		}
		net.minecraft.world.entity.MobSpawnType how = a.has("spawn_type") && a.get("spawn_type").getAsString().equals("triggered")
				? net.minecraft.world.entity.MobSpawnType.TRIGGERED : net.minecraft.world.entity.MobSpawnType.EVENT;
		double radius = a.get("radius").getAsDouble();
		for (int i = 0; i < count; i++) {
			spawnNear(anchor, type.get(), radius * 0.5, radius, how, a.has("leave_after") ? a.get("leave_after").getAsInt() : 0);
		}
	}

	/** Spawns one `type` on the ground `min`-`max` blocks from the player, at about their height (so it works in caves). */
	private static Entity spawnNear(ServerPlayer p, EntityType<?> type, double min, double max, net.minecraft.world.entity.MobSpawnType how, int leaveAfter) {
		ServerLevel level = p.serverLevel();
		var r = p.getRandom();
		for (int tries = 0; tries < 12; tries++) {
			double ang = r.nextDouble() * Math.PI * 2;
			double d = min + r.nextDouble() * Math.max(0.1, max - min);
			BlockPos pos = standable(level, (int) Math.floor(p.getX() + Math.cos(ang) * d), p.getBlockY(), (int) Math.floor(p.getZ() + Math.sin(ang) * d));
			if (pos == null) {
				continue;
			}
			Entity e = type.spawn(level, pos, how);
			if (e != null) {
				e.setYRot(r.nextFloat() * 360);
				WheelStatus.markLeaving(e, leaveAfter);
				return e;
			}
		}
		return null;
	}

	/** A spot with room for a mob, searching 6 up and 10 down from y. */
	private static BlockPos standable(ServerLevel level, int x, int y, int z) {
		for (int dy = 6; dy >= -10; dy--) {
			BlockPos pos = new BlockPos(x, y + dy, z);
			if (level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)
					&& level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getFluidState(pos).isEmpty()
					&& level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) {
				return pos;
			}
		}
		return null;
	}

	/** Mob ambush: a ring of hostile mobs round the player. */
	private static void ambush(ServerPlayer p, JsonObject a) {
		List<EntityType<?>> mobs = new ArrayList<>();
		if (a.has("mobs")) {
			a.getAsJsonArray("mobs").forEach(x -> EntityType.byString(x.getAsString()).ifPresent(mobs::add));
		}
		if (mobs.isEmpty()) {
			mobs.add(EntityType.ZOMBIE);
		}
		int count = a.has("count") ? a.get("count").getAsInt() : 6;
		double radius = a.has("radius") ? a.get("radius").getAsDouble() : 7;
		for (int i = 0; i < count; i++) {
			spawnNear(p, mobs.get(p.getRandom().nextInt(mobs.size())), radius * 0.7, radius, net.minecraft.world.entity.MobSpawnType.EVENT, 0);
		}
		p.serverLevel().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 0.6f, 1.2f);
	}

	/** Loot piñata: rolls a few chest loot tables and sprays the items out round the player. */
	/** {"type":"ammo", "magazines":5}: that many magazines for every gun each player is carrying (TACZ). */
	private static void ammo(ServerPlayer p, JsonObject a) {
		if (!Guns.available()) {
			return;
		}
		var stacks = GunSupply.ammoFor(p, a.has("magazines") ? Math.max(1, a.get("magazines").getAsInt()) : 5);
		if (stacks.isEmpty()) {
			p.sendSystemMessage(Component.literal("No gun, no ammo. Better luck next spin.").withStyle(ChatFormatting.GRAY));
			return;
		}
		GunSupply.give(p, stacks, "Locked and loaded:");
	}

	private static void loot(ServerPlayer p, JsonObject a) {
		ServerLevel level = p.serverLevel();
		List<String> tables = new ArrayList<>();
		a.getAsJsonArray("tables").forEach(x -> tables.add(x.getAsString()));
		if (tables.isEmpty()) {
			return;
		}
		int rolls = a.has("rolls") ? a.get("rolls").getAsInt() : 2;
		var r = p.getRandom();
		Vec3 at = p.position().add(0, 2.5, 0);
		for (int i = 0; i < rolls; i++) {
			var key = net.minecraft.resources.ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse(tables.get(r.nextInt(tables.size()))));
			var table = server.reloadableRegistries().getLootTable(key);
			var params = new net.minecraft.world.level.storage.loot.LootParams.Builder(level)
					.withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN, at)
					.withLuck(p.getLuck())
					.create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.CHEST);
			for (ItemStack stack : table.getRandomItems(params)) {
				ItemEntity drop = new ItemEntity(level, at.x, at.y, at.z, stack);
				double ang = r.nextDouble() * Math.PI * 2;
				drop.setDeltaMovement(Math.cos(ang) * 0.25, 0.35 + r.nextDouble() * 0.2, Math.sin(ang) * 0.25);
				drop.setPickUpDelay(15);
				level.addFreshEntity(drop);
			}
		}
		level.sendParticles(net.minecraft.core.particles.ParticleTypes.FIREWORK, at.x, at.y, at.z, 40, 0.3, 0.3, 0.3, 0.25);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 1f, 1f);
	}

	private static void drop(ServerPlayer anchor, JsonObject a) {
		if (anchor == null) {
			return;
		}
		Optional<Item> item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(a.get("item").getAsString()));
		if (item.isEmpty()) {
			return;
		}
		Vec3 at = spot(anchor, 4);
		ItemEntity drop = new ItemEntity(anchor.serverLevel(), at.x, at.y + 0.5, at.z, new ItemStack(item.get(), a.has("count") ? a.get("count").getAsInt() : 1));
		drop.setDefaultPickUpDelay();
		anchor.serverLevel().addFreshEntity(drop);
	}

	private static void runCommand(ServerPlayer anchor, String command) {
		CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
		if (anchor != null) {
			source = source.withLevel(anchor.serverLevel()).withPosition(anchor.position());
		}
		server.getCommands().performPrefixedCommand(source, command);
	}

	// ------------------------------------------------------------------ files

	static boolean active() {
		return applyAt >= 0 || midApplyAt >= 0;
	}

	private static Path file() {
		return FMLPaths.CONFIGDIR.get().resolve("bubble-wheel.json");
	}

	private static void load() {
		try {
			JsonObject root;
			if (Files.exists(file())) {
				root = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
				if (addNewDefaults(root)) {
					Files.writeString(file(), GSON.toJson(root));
				}
			} else {
				root = defaults();
				Files.createDirectories(file().getParent());
				Files.writeString(file(), GSON.toJson(root));
			}
			slices = parse(root.getAsJsonArray("slices"));
			midSlices = root.has("midrun") ? parse(root.getAsJsonArray("midrun")) : new ArrayList<>();
		} catch (Exception e) {
			Bubble.LOG.error("Could not read config/bubble-wheel.json", e);
		}
	}

	private static List<Slice> parse(JsonArray arr) {
		List<Slice> out = new ArrayList<>();
		{
			for (JsonElement se : arr) {
				JsonObject so = se.getAsJsonObject();
				List<JsonObject> actions = new ArrayList<>();
				if (so.has("actions")) {
					so.getAsJsonArray("actions").forEach(x -> actions.add(x.getAsJsonObject()));
				}
				String color = so.has("color") ? so.get("color").getAsString() : "#7CC4A4";
				out.add(new Slice(so.get("name").getAsString(), 0xFF000000 | Integer.parseInt(color.replace("#", ""), 16),
						so.has("weight") ? Math.max(0, so.get("weight").getAsInt()) : 1, !so.has("enabled") || so.get("enabled").getAsBoolean(), actions));
			}
		}
		return out;
	}

	/** Slices added in a newer version get added to an existing wheel file once (a deleted one stays deleted). */
	private static boolean addNewDefaults(JsonObject root) {
		JsonArray seen;
		if (root.has("defaultsSeen")) {
			seen = root.getAsJsonArray("defaultsSeen");
		} else {
			seen = new JsonArray();
			root.getAsJsonArray("slices").forEach(s -> seen.add(s.getAsJsonObject().get("name").getAsString()));
			root.add("defaultsSeen", seen);
		}
		boolean changed = !root.has("defaultsSeenSaved");
		root.addProperty("defaultsSeenSaved", true);
		for (String list : new String[] {"slices", "midrun"}) {
			if (!root.has(list)) {
				root.add(list, new JsonArray());
				changed = true;
			}
			String prefix = list.equals("midrun") ? "mid:" : "";
			for (JsonElement d : defaults().getAsJsonArray(list)) {
				String name = prefix + d.getAsJsonObject().get("name").getAsString();
				if (!seen.contains(new com.google.gson.JsonPrimitive(name))) {
					seen.add(name);
					root.getAsJsonArray(list).add(d);
					Bubble.LOG.info("Wheel: added the '{}' slice", name);
					changed = true;
				}
			}
		}
		return changed;
	}

	private static JsonObject defaults() {
		return JsonParser.parseString("""
				{
				  "slices": [
				    {"name": "Warship", "color": "#4A90D9", "actions": [
				      {"type": "summon", "entity": "immersive_aircraft:warship"},
				      {"type": "drop", "item": "minecraft:coal_block", "count": 64}]},
				    {"name": "Artifact for everyone", "color": "#E0A030", "actions": [
				      {"type": "give_random", "tag": "artifacts:artifacts", "count": 1}]},
				    {"name": "Lucky blocks", "color": "#F2D23C", "actions": [
				      {"type": "give", "item": "lucky:lucky_block", "count": 8}]},
				    {"name": "Charm of Shrinking", "color": "#B565E0", "actions": [
				      {"type": "give", "item": "artifacts:charm_of_shrinking", "count": 1}]},
				    {"name": "Low gravity", "color": "#8FD3FF", "actions": [{"type": "modifier", "id": "low_gravity"}]},
				    {"name": "Tiny mode", "color": "#7CE07C", "actions": [{"type": "modifier", "id": "tiny"}]},
				    {"name": "Glass cannon", "color": "#FF5A5A", "actions": [{"type": "modifier", "id": "glass_cannon"}]},
				    {"name": "Swap!", "color": "#D36BFF", "actions": [{"type": "modifier", "id": "swap"}]},
				    {"name": "Speedrun", "color": "#FFB347", "actions": [{"type": "modifier", "id": "speedrun"}]},
				    {"name": "Lifesteal", "color": "#C2185B", "actions": [{"type": "modifier", "id": "lifesteal"}]},
				    {"name": "Charged railgun", "color": "#5EC8E5", "actions": [
				      {"type": "give", "item": "oritech:portable_laser", "count": 1, "charged": true}]},
				    {"name": "Legendary sword", "color": "#FFD700", "actions": [
				      {"type": "give_one_of", "items": ["simplyswords:watcher_claymore", "simplyswords:the_devourer", "simplyswords:brimstone_claymore", "simplyswords:watching_warglaive", "simplyswords:storms_edge", "simplyswords:stormbringer", "simplyswords:bramblethorn", "simplyswords:mjolnir", "simplyswords:emberblade", "simplyswords:hearthflame", "simplyswords:twisted_blade", "simplyswords:soulkeeper", "simplyswords:soulstealer", "simplyswords:soulrender", "simplyswords:soulpyre", "simplyswords:frostfall", "simplyswords:molten_edge", "simplyswords:livyatan", "simplyswords:icewhisper", "simplyswords:arcanethyst", "simplyswords:thunderbrand", "simplyswords:awakened_lichblade", "simplyswords:shadowsting", "simplyswords:harbinger", "simplyswords:sunfire", "simplyswords:whisperwind", "simplyswords:emberlash", "simplyswords:hiveheart", "simplyswords:stars_edge", "simplyswords:wickpiercer", "simplyswords:dreadtide", "simplyswords:tempest", "simplyswords:flamewind", "simplyswords:ribboncleaver", "simplyswords:magiscythe", "simplyswords:enigma", "simplyswords:magispear", "simplyswords:magiblade", "simplyswords:caelestis", "simplyswords:wraithfang", "simplyswords:bloodwake", "simplyswords:riftmane"]}]},
				    {"name": "Backpacks", "color": "#8D6E63", "actions": [{"type": "give", "item": "sophisticatedbackpacks:diamond_backpack"}]},
				    {"name": "Waystone network", "color": "#26C6DA", "actions": [
				      {"type": "give", "item": "waystones:warp_stone"}, {"type": "give", "item": "waystones:waystone", "count": 2}]},
				    {"name": "Air force", "color": "#90A4AE", "actions": [
				      {"type": "give_one_of", "items": ["immersive_aircraft:biplane", "immersive_aircraft:gyrodyne"]},
				      {"type": "give", "item": "minecraft:coal_block", "count": 16}]},
				    {"name": "Armed and dangerous", "color": "#546E7A", "actions": [
				      {"type": "gun", "guns": ["glock_17", "m1911", "deagle", "deagle_golden", "rhino357", "taurus500"]}]},
				    {"name": "Bow master", "color": "#AED581", "actions": [
				      {"type": "give_one_of", "items": ["simplybows:vine_bow/vine_bow", "simplybows:ice_bow/ice_bow", "simplybows:bubble_bow/bubble_bow", "simplybows:bee_bow/bee_bow", "simplybows:blossom_bow/blossom_bow", "simplybows:earth_bow/earth_bow", "simplybows:echo_bow/echo_bow"]}, {"type": "give", "item": "minecraft:arrow", "count": 64}]},
				    {"name": "Relic haul", "color": "#FFB74D", "actions": [
				      {"type": "give_random", "tag": "artifacts:artifacts"}, {"type": "give_random", "tag": "artifacts:artifacts"},
				      {"type": "give_random", "tag": "artifacts:artifacts"}]},
				    {"name": "Portal kit", "color": "#FFD54F", "actions": [
				      {"type": "drop", "item": "minecraft:glowstone", "count": 14}, {"type": "drop", "item": "minecraft:water_bucket"},
				      {"type": "drop", "item": "minecraft:water_bucket"}, {"type": "drop", "item": "minecraft:diamond"},
				      {"type": "drop", "item": "minecraft:poppy", "count": 12}]},
				    {"name": "Treasure hunt", "color": "#A1887F", "actions": [{"type": "give", "item": "explorerscompass:explorerscompass"}]},
				    {"name": "Band camp", "color": "#F48FB1", "actions": [
				      {"type": "give_one_of", "items": ["immersive_melodies:lute", "immersive_melodies:piano", "immersive_melodies:flute", "immersive_melodies:didgeridoo", "immersive_melodies:bagpipe", "immersive_melodies:vielle", "immersive_melodies:trumpet", "immersive_melodies:triangle", "immersive_melodies:tiny_drum"]}]},
				    {"name": "Oritech head start", "color": "#7986CB", "actions": [
				      {"type": "give", "item": "oritech:exo_jetpack", "charged": true}, {"type": "give", "item": "oritech:portable_laser", "charged": true}]},
				    {"name": "Mythic loot", "color": "#BA68C8", "actions": [{"type": "apoth_loot", "min_rarity": 3}]},
				    {"name": "UHC mode", "color": "#B71C1C", "actions": [{"type": "modifier", "id": "uhc"}]},
				    {"name": "Hard world", "color": "#6D4C41", "actions": [{"type": "modifier", "id": "hard_world"}]},
				    {"name": "Boss bounty", "color": "#FFAB00", "actions": [{"type": "modifier", "id": "boss_bounty"}]},
				    {"name": "Double loot", "color": "#43A047", "actions": [{"type": "modifier", "id": "double_loot"}]},
				    {"name": "Eternal night", "color": "#1A237E", "actions": [{"type": "modifier", "id": "eternal_night"}]},
				    {"name": "Miner's luck", "color": "#78909C", "actions": [{"type": "modifier", "id": "miners_luck"}]},
				    {"name": "No hunger", "color": "#FFCC80", "actions": [{"type": "modifier", "id": "no_hunger"}]},
				    {"name": "Pacifist start", "color": "#81C784", "actions": [{"type": "modifier", "id": "pacifist"}]},
				    {"name": "Explosive mobs", "color": "#FF6F00", "actions": [{"type": "modifier", "id": "explosive"}]},
				    {"name": "Sky island start", "color": "#80DEEA", "actions": [{"type": "modifier", "id": "sky_island"}]}
				  ],
				  "midrun": [
				    {"name": "Upside down", "color": "#9C6ADE", "actions": [{"type": "fx", "kind": "upside_down", "seconds": 300}]},
				    {"name": "Ice ground", "color": "#A8E6FF", "actions": [{"type": "fx", "kind": "ice", "seconds": 60}]},
				    {"name": "Super speed", "color": "#FFE066", "actions": [{"type": "effect", "effect": "minecraft:speed", "seconds": 60, "amplifier": 6}]},
				    {"name": "Sky drop (one of you)", "color": "#4FC3F7", "actions": [{"type": "skydrop", "height": 100, "who": "random"}]},
				    {"name": "Sky drop (everyone)", "color": "#0288D1", "actions": [{"type": "skydrop", "height": 100}]},
				    {"name": "Girl Power", "color": "#F5A9B8", "actions": [{"type": "effect", "effect": "estrogen:estrogen", "seconds": 300}]},
				    {"name": "Jackpot", "color": "#7DF0C8", "actions": [{"type": "matter", "amount": 150}]},
				    {"name": "Hotbar shuffle", "color": "#B388FF", "actions": [{"type": "shuffle_hotbar"}]},
				    {"name": "Lights out", "color": "#37474F", "actions": [{"type": "effect", "effect": "minecraft:blindness", "seconds": 60}]},
				    {"name": "A Quiet Place", "color": "#0D5C63", "actions": [
				      {"type": "summon", "entity": "minecraft:warden", "radius": 16, "spawn_type": "triggered", "leave_after": 180}]},
				    {"name": "Meteor rain", "color": "#FF7043", "actions": [{"type": "status", "kind": "meteor_rain", "seconds": 30}]},
				    {"name": "Locked and loaded", "color": "#C9A227", "actions": [{"type": "ammo", "magazines": 5}]},
				    {"name": "Mob ambush", "color": "#8BC34A", "actions": [
				      {"type": "ambush", "mobs": ["minecraft:zombie", "minecraft:skeleton", "minecraft:spider", "minecraft:creeper"], "count": 6, "radius": 8}]},
				    {"name": "Hunger drain", "color": "#A1887F", "actions": [{"type": "effect", "effect": "minecraft:hunger", "seconds": 120, "amplifier": 4}]},
				    {"name": "Random gravity", "color": "#7E57C2", "actions": [{"type": "status", "kind": "random_gravity", "seconds": 60}]},
				    {"name": "Floor is lava", "color": "#FF3D00", "actions": [{"type": "status", "kind": "floor_is_lava", "seconds": 30}]},
				    {"name": "Butterfingers", "color": "#FFEB3B", "actions": [{"type": "status", "kind": "butterfingers", "seconds": 60}]},
				    {"name": "TNT rain", "color": "#E53935", "actions": [{"type": "status", "kind": "tnt_rain", "seconds": 20}]},
				    {"name": "Lightning storm", "color": "#FFF176", "actions": [{"type": "status", "kind": "lightning", "seconds": 30}]},
				    {"name": "Midas touch", "color": "#FFC107", "actions": [{"type": "status", "kind": "midas", "seconds": 120}]},
				    {"name": "Flight", "color": "#81D4FA", "actions": [{"type": "status", "kind": "flight", "seconds": 60}]},
				    {"name": "Heal & feed", "color": "#EF9A9A", "actions": [{"type": "heal"}]},
				    {"name": "Loot pinata", "color": "#F06292", "actions": [
				      {"type": "loot", "rolls": 2, "tables": ["minecraft:chests/simple_dungeon", "minecraft:chests/abandoned_mineshaft", "minecraft:chests/desert_pyramid", "minecraft:chests/jungle_temple", "minecraft:chests/buried_treasure", "minecraft:chests/bastion_treasure", "minecraft:chests/end_city_treasure", "minecraft:chests/ancient_city"]}]},
				    {"name": "Double Matter", "color": "#26A69A", "actions": [{"type": "status", "kind": "double_matter", "seconds": 300}]},
				    {"name": "Item gift", "color": "#CE93D8", "actions": [
				      {"type": "give_one_of", "items": ["minecraft:totem_of_undying", "minecraft:enchanted_golden_apple", "minecraft:netherite_ingot", "minecraft:elytra", "minecraft:trident", "minecraft:nether_star", "minecraft:heart_of_the_sea", "minecraft:diamond_block", "minecraft:shulker_box"]}]},
				    {"name": "Hotbar swap", "color": "#9575CD", "actions": [{"type": "swap_hotbars"}]},
				    {"name": "Tiny or giant", "color": "#66BB6A", "actions": [{"type": "status", "kind": "size", "seconds": 180}]},
				    {"name": "Random teleport", "color": "#4DD0E1", "actions": [{"type": "teleport", "radius": 50}]},
				    {"name": "Summon a boss", "color": "#6A1B9A", "actions": [{"type": "boss", "warning": 10, "distance": 20,
				      "bosses": ["cataclysm:ender_guardian", "cataclysm:netherite_monstrosity", "cataclysm:ignis", "cataclysm:maledictus", "cataclysm:ancient_remnant", "cataclysm:the_harbinger{Is_Act:1b}"]}]},
				    {"name": "Chickens everywhere", "color": "#FFF3C4", "actions": [
				      {"type": "summon", "entity": "minecraft:chicken", "count": 30, "radius": 14, "leave_after": 120}]},
				    {"name": "Case party", "color": "#FFB74D", "actions": [{"type": "cases", "count": 5}]},
				    {"name": "Bonus spins", "color": "#CE93D8", "actions": [{"type": "give", "item": "bubble:wheel_spin", "count": 2}]},
				    {"name": "Ragdoll everyone", "color": "#90A4AE", "actions": [{"type": "ragdoll", "seconds": 10}]},
				    {"name": "Fragile mode", "color": "#ECEFF1", "actions": [{"type": "status", "kind": "fragile", "seconds": 180}]},
				    {"name": "Yeet", "color": "#FF8A65", "actions": [{"type": "yeet", "who": "random", "speed": 30}]},
				    {"name": "Ragdoll roulette", "color": "#A1887F", "actions": [{"type": "status", "kind": "ragdoll_roulette", "seconds": 120}]}
				  ]
				}""").getAsJsonObject();
	}

	private static void readState() {
		pending = false;
		result = -1;
		resultName = null;
		rewarded.clear();
		try {
			if (Files.exists(stateFile)) {
				JsonObject o = JsonParser.parseString(Files.readString(stateFile)).getAsJsonObject();
				pending = o.has("pending") && o.get("pending").getAsBoolean();
				result = o.has("result") ? o.get("result").getAsInt() : -1;
				resultName = o.has("resultName") ? o.get("resultName").getAsString() : null;
				if (o.has("rewarded")) {
					o.getAsJsonArray("rewarded").forEach(x -> rewarded.add(x.getAsString()));
				}
			}
		} catch (Exception e) {
			Bubble.LOG.error("Could not read {}", stateFile, e);
		}
	}

	private static void saveState() {
		if (stateFile == null) {
			return;
		}
		try {
			JsonObject o = new JsonObject();
			o.addProperty("pending", pending);
			o.addProperty("result", result);
			if (resultName != null) {
				o.addProperty("resultName", resultName);
			}
			JsonArray r = new JsonArray();
			rewarded.forEach(r::add);
			o.add("rewarded", r);
			Files.writeString(stateFile, GSON.toJson(o));
		} catch (Exception e) {
			Bubble.LOG.error("Could not save {}", stateFile, e);
		}
	}
}
