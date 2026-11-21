package moonahmc.bubble;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import moonahmc.bubble.net.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pre-game setup (the "Next run" tab): how many lucky blocks generate, how often each mod's structures appear (or not at
 * all), the next world's difficulty and seed, and the rainbow / nuke creepers (those apply right away).
 *
 * World generation settings can't change an existing world, so they're written as a data pack (bubble-next-datapack/
 * in the server folder) every time they change, and WorldArchiver puts that pack into the next world when a run ends.
 */
public final class Pregame {
	public static final String[] AMOUNTS = {"Off", "Rare", "Normal", "Common", "Lots"};
	public static final String[] MOB_AMOUNTS = {"Off", "Rare", "Normal", "Lots"};
	public static final int[] SPEEDS = {1, 2, 5, 10, 25, 50, 100};
	public static final String[] DIFFICULTIES = {"Keep", "Peaceful", "Easy", "Normal", "Hard"};
	public static final String[] ON_OFF = {"Off", "On"};

	/** Lucky blocks: one per this many chunks on the surface / in caves. */
	private static final int[] LUCKY_SURFACE = {0, 48, 16, 6, 2};
	private static final int[] LUCKY_CAVES = {0, 64, 24, 8, 3};
	/** Structures: how much more (or less) often than normal. */
	private static final double[] STRUCTURE_MULT = {0, 0.4, 1, 2, 4};
	/** Creepers per player per hour. */
	private static final double[] RAINBOW_PER_HOUR = {0, 2, 6, 15};
	private static final double[] NUKE_PER_HOUR = {0, 0.15, 0.5, 2};
	private static final double[] GATLING_PER_HOUR = {0, 1, 3, 8};
	private static final double[] WATCHER_PER_HOUR = {0, 0.5, 1.5, 4};
	/** Evil Watchers follow the Watchers setting, at a seventh of the rate. */
	private static final double EVIL_WATCHER_SHARE = 1.0 / 7;
	private static final double[] JOCKEY_PER_HOUR = {0, 1, 3, 8};
	private static final double[] SWARM_PER_HOUR = {0, 1, 3, 8};

	static final String STAGED = "bubble-next-datapack";
	private static final String PACK = "bubble_pregame";
	private static final ResourceLocation LUCKY_BLOCK = ResourceLocation.fromNamespaceAndPath("lucky", "lucky_block");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	static final class Data {
		int lucky = 2;
		int rainbow = 2;
		int nuke = 1;
		int speed = 100;
		/** -1 = keep whatever the server has, else Difficulty id. */
		int difficulty = -1;
		int gatling = 1;
		int watchers = 1;
		int jockeys = 1;
		int swarm = 1;
		/** 1 = spin the start-of-run wheel in new worlds. */
		int wheel = 1;
		/** 1 = new worlds start with the ready-up lobby. */
		int lobby = 1;
		/** Goals game mode id for the next world. */
		String mode = "full_run";
		Map<String, Integer> structures = new TreeMap<>();
	}

	private static Data data = new Data();
	private static Path file;
	private static MinecraftServer server;

	private Pregame() {
	}

	static void init() {
		file = FMLPaths.CONFIGDIR.get().resolve("bubble-pregame.json");
		load();
		var bus = NeoForge.EVENT_BUS;
		bus.addListener((ServerStartedEvent e) -> {
			server = e.getServer();
			applyToNewWorld(server);
			stage(server);
		});
		bus.addListener((ServerStoppedEvent e) -> server = null);
		bus.addListener((ServerTickEvent.Post e) -> {
			if (e.getServer().getTickCount() % 20 == 0) {
				spawnCreepers(e.getServer());
			}
		});
		bus.addListener((ExplosionEvent.Start e) -> {
			var source = e.getExplosion().getDirectSourceEntity();
			if (source instanceof RainbowCreeper rc) {
				e.setCanceled(true);
				rc.popHarmlessly();
			} else if (source instanceof NukeCreeper nc) {
				e.setCanceled(true);
				nc.detonate();
			}
		});
		bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
			if (e.getEntity() instanceof ServerPlayer p) {
				p.server.execute(() -> sendState(p));
			}
		});
	}

	static double rainbowSpeed() {
		return data.speed;
	}

	static boolean wheelEnabled() {
		return data.wheel == 1;
	}

	static String gameMode() {
		return Goals.modeIds().contains(data.mode) ? data.mode : Goals.modeIds().isEmpty() ? "full_run" : Goals.modeIds().get(0);
	}

	static boolean lobbyEnabled() {
		return data.lobby == 1;
	}

	// ------------------------------------------------------------------ settings

	private static void load() {
		try {
			if (Files.exists(file)) {
				Data d = GSON.fromJson(Files.readString(file), Data.class);
				if (d != null) {
					if (d.structures == null) {
						d.structures = new TreeMap<>();
					}
					data = d;
				}
			}
		} catch (Exception e) {
			Bubble.LOG.error("Could not read config/bubble-pregame.json; using defaults", e);
		}
		clamp();
	}

	private static void save() {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(data));
		} catch (IOException e) {
			Bubble.LOG.error("Could not save config/bubble-pregame.json", e);
		}
	}

	private static void clamp() {
		data.lucky = Math.max(0, Math.min(AMOUNTS.length - 1, data.lucky));
		data.rainbow = Math.max(0, Math.min(MOB_AMOUNTS.length - 1, data.rainbow));
		data.nuke = Math.max(0, Math.min(MOB_AMOUNTS.length - 1, data.nuke));
		data.speed = Math.max(1, Math.min(100, data.speed));
		data.difficulty = Math.max(-1, Math.min(3, data.difficulty));
		data.gatling = Math.max(0, Math.min(MOB_AMOUNTS.length - 1, data.gatling));
		data.watchers = Math.max(0, Math.min(MOB_AMOUNTS.length - 1, data.watchers));
		data.jockeys = Math.max(0, Math.min(MOB_AMOUNTS.length - 1, data.jockeys));
		data.swarm = Math.max(0, Math.min(MOB_AMOUNTS.length - 1, data.swarm));
		data.wheel = data.wheel == 0 ? 0 : 1;
		data.lobby = data.lobby == 0 ? 0 : 1;
		if (data.mode == null) {
			data.mode = "full_run";
		}
		data.structures.replaceAll((k, v) -> Math.max(0, Math.min(AMOUNTS.length - 1, v)));
		data.structures.values().removeIf(v -> v == 2);
	}

	private static List<PregamePayloads.Group> groups(MinecraftServer s) {
		Map<String, Integer> counts = new TreeMap<>();
		for (ResourceKey<StructureSet> key : s.registryAccess().registryOrThrow(Registries.STRUCTURE_SET).registryKeySet()) {
			counts.merge(key.location().getNamespace(), 1, Integer::sum);
		}
		List<PregamePayloads.Group> out = new ArrayList<>();
		counts.forEach((ns, n) -> out.add(new PregamePayloads.Group(ns, modName(ns), n, data.structures.getOrDefault(ns, 2))));
		out.sort(Comparator.comparing((PregamePayloads.Group g) -> !g.namespace().equals("minecraft")).thenComparing(PregamePayloads.Group::name));
		return out;
	}

	private static String modName(String ns) {
		if (ns.equals("minecraft")) {
			return "Vanilla";
		}
		return ModList.get().getModContainerById(ns).map(c -> c.getModInfo().getDisplayName()).orElse(ns);
	}

	private static PregamePayloads.Settings settings(MinecraftServer s) {
		return new PregamePayloads.Settings(data.lucky, data.rainbow, data.nuke, data.speed, data.difficulty + 1,
				BubbleServer.config.nextSeed == null ? "" : BubbleServer.config.nextSeed, groups(s), data.gatling, data.wheel, data.lobby, data.watchers,
				Math.max(0, Goals.modeIds().indexOf(gameMode())), Goals.modeNames(), data.jockeys, data.swarm);
	}

	static void sendState(ServerPlayer p) {
		if (ServerPlayNetworking.canSend(p, PregamePayloads.State.ID)) {
			ServerPlayNetworking.send(p, new PregamePayloads.State(p.hasPermissions(2), settings(p.server)));
		}
	}

	private static void broadcast() {
		if (server != null) {
			server.getPlayerList().getPlayers().forEach(Pregame::sendState);
		}
	}

	static void update(ServerPlayer p, PregamePayloads.Settings s) {
		if (!p.hasPermissions(2)) {
			sendState(p);
			return;
		}
		boolean worldgen = s.lucky() != data.lucky || s.groups().stream().anyMatch(g -> g.level() != data.structures.getOrDefault(g.namespace(), 2));
		data.lucky = s.lucky();
		data.rainbow = s.rainbow();
		data.nuke = s.nuke();
		data.speed = s.speed();
		data.difficulty = s.difficulty() - 1;
		data.gatling = s.gatling();
		data.wheel = s.wheel();
		data.lobby = s.lobby();
		data.watchers = s.watchers();
		data.jockeys = Math.max(0, Math.min(MOB_AMOUNTS.length - 1, s.jockeys()));
		data.swarm = Math.max(0, Math.min(MOB_AMOUNTS.length - 1, s.swarm()));
		if (s.mode() >= 0 && s.mode() < Goals.modeIds().size()) {
			data.mode = Goals.modeIds().get(s.mode());
		}
		for (PregamePayloads.Group g : s.groups()) {
			data.structures.put(g.namespace(), g.level());
		}
		clamp();
		save();
		String seed = s.nextSeed().trim().replaceAll("[^0-9A-Za-z_-]", "");
		if (!seed.equals(BubbleServer.config.nextSeed)) {
			BubbleServer.config.nextSeed = seed;
			BubbleServer.saveConfig();
		}
		if (worldgen) {
			stage(p.server);
		}
		broadcast();
	}

	// ------------------------------------------------------------------ creepers

	private static void spawnCreepers(MinecraftServer s) {
		if (Lobby.active()) {
			return; // nobody gets ambushed while waiting to start
		}
		for (ServerLevel level : s.getAllLevels()) {
			if (level.getDifficulty() == Difficulty.PEACEFUL || !level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) {
				continue;
			}
			for (ServerPlayer p : level.players()) {
				if (!BubbleServer.counts(p)) {
					continue;
				}
				trySpawn(level, p, ModContent.RAINBOW_CREEPER.get(), RAINBOW_PER_HOUR[data.rainbow], 3, 64);
				trySpawn(level, p, ModContent.NUKE_CREEPER.get(), NUKE_PER_HOUR[data.nuke], 1, 160);
				trySpawn(level, p, ModContent.GATLING_SKELETON.get(), GATLING_PER_HOUR[data.gatling], 2, 64);
				trySpawn(level, p, ModContent.WATCHER.get(), WATCHER_PER_HOUR[data.watchers], 1, 96);
				trySpawn(level, p, ModContent.EVIL_WATCHER.get(), WATCHER_PER_HOUR[data.watchers] * EVIL_WATCHER_SHARE, 1, 128);
				trySpawn(level, p, ModContent.JOCKEY_JOCKEY.get(), JOCKEY_PER_HOUR[data.jockeys], 2, 64);
				trySpawn(level, p, ModContent.SWARM_ZOMBIE.get(), SWARM_PER_HOUR[data.swarm], 2, 64);
			}
		}
	}

	private static void trySpawn(ServerLevel level, ServerPlayer p, EntityType<? extends Mob> type, double perHour, int cap, int capRange) {
		if (perHour <= 0 || level.random.nextDouble() >= perHour / 3600.0) {
			return;
		}
		if (level.getEntities(type, p.getBoundingBox().inflate(capRange), e -> true).size() >= cap) {
			return;
		}
		for (int attempt = 0; attempt < 12; attempt++) {
			double angle = level.random.nextDouble() * Math.PI * 2;
			double dist = 18 + level.random.nextDouble() * 18;
			int x = (int) Math.floor(p.getX() + Math.cos(angle) * dist);
			int z = (int) Math.floor(p.getZ() + Math.sin(angle) * dist);
			int startY = (int) p.getY() + level.random.nextInt(13) - 6;
			for (int y = startY; y > startY - 12 && y > level.getMinBuildHeight(); y--) {
				BlockPos pos = new BlockPos(x, y, z);
				BlockPos below = pos.below();
				if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP) && level.getBlockState(pos).isAir()
						&& level.getBlockState(pos.above()).isAir() && level.getFluidState(pos).isEmpty()
						&& level.getNearestPlayer(x + 0.5, y, z + 0.5, 16, false) == null
						&& level.noCollision(type.getSpawnAABB(x + 0.5, y, z + 0.5))
						&& ((type != ModContent.GATLING_SKELETON.get() && type != ModContent.WATCHER.get() && type != ModContent.EVIL_WATCHER.get()
								&& type != ModContent.JOCKEY_JOCKEY.get() && type != ModContent.SWARM_ZOMBIE.get())
								|| net.minecraft.world.entity.monster.Monster.isDarkEnoughToSpawn(level, pos, level.random))
						&& ((type != ModContent.WATCHER.get() && type != ModContent.EVIL_WATCHER.get()) || level.getBlockState(pos.above(2)).isAir())) {
					Mob mob = type.create(level);
					if (mob == null) {
						return;
					}
					mob.moveTo(x + 0.5, y, z + 0.5, level.random.nextFloat() * 360, 0);
					mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.NATURAL, null);
					level.addFreshEntityWithPassengers(mob);
					if (mob instanceof NukeCreeper nc) {
						for (ServerPlayer q : level.players()) {
							if (q.distanceToSqr(mob) < 64 * 64) {
								q.displayClientMessage(Component.literal(nc.isUnstable() ? "☢ Something nearby is ticking very, very fast..."
										: "☢ You hear a deep, slow hiss somewhere nearby...").withStyle(nc.isUnstable() ? ChatFormatting.RED : ChatFormatting.DARK_GREEN), true);
							}
						}
					}
					return;
				}
			}
		}
	}

	// ------------------------------------------------------------------ the next world

	/** Difficulty only changes for a brand-new world (a marker file remembers that it was done). */
	private static void applyToNewWorld(MinecraftServer s) {
		Path marker = s.getWorldPath(LevelResource.ROOT).resolve("bubble-pregame-applied");
		if (Files.exists(marker)) {
			return;
		}
		try {
			Files.writeString(marker, "pre-game settings applied\n");
		} catch (IOException ignored) {
			// not important
		}
		if (data.difficulty >= 0) {
			s.setDifficulty(Difficulty.byId(data.difficulty), true);
			Bubble.LOG.info("Pre-game: difficulty set to {}", Difficulty.byId(data.difficulty).getKey());
		}
	}

	/** Writes bubble-next-datapack/ for the next world from the current settings. */
	static void stage(MinecraftServer s) {
		Path dir = FMLPaths.GAMEDIR.get().resolve(STAGED);
		try {
			deleteTree(dir);
			Files.createDirectories(dir);
			JsonObject manifest = new JsonObject();
			write(dir, "pack.mcmeta", JsonParser.parseString("{\"pack\":{\"pack_format\":48,\"description\":\"Bubble pre-game settings\"}}"));
			int files = 0;

			if (data.lucky > 0 && BuiltInRegistries.BLOCK.containsKey(LUCKY_BLOCK)) {
				write(dir, "data/bubble/worldgen/configured_feature/lucky_block.json", JsonParser.parseString(
						"{\"type\":\"minecraft:simple_block\",\"config\":{\"to_place\":{\"type\":\"minecraft:simple_state_provider\","
								+ "\"state\":{\"Name\":\"lucky:lucky_block\"}}}}"));
				write(dir, "data/bubble/worldgen/placed_feature/lucky_block_surface.json", JsonParser.parseString(
						"{\"feature\":\"bubble:lucky_block\",\"placement\":[{\"type\":\"minecraft:rarity_filter\",\"chance\":" + LUCKY_SURFACE[data.lucky] + "},"
								+ "{\"type\":\"minecraft:in_square\"},{\"type\":\"minecraft:heightmap\",\"heightmap\":\"MOTION_BLOCKING_NO_LEAVES\"},"
								+ "{\"type\":\"minecraft:block_predicate_filter\",\"predicate\":{\"type\":\"minecraft:all_of\",\"predicates\":["
								+ "{\"type\":\"minecraft:matching_blocks\",\"blocks\":\"minecraft:air\"},"
								+ "{\"type\":\"minecraft:has_sturdy_face\",\"direction\":\"up\",\"offset\":[0,-1,0]}]}},{\"type\":\"minecraft:biome\"}]}"));
				write(dir, "data/bubble/worldgen/placed_feature/lucky_block_caves.json", JsonParser.parseString(
						"{\"feature\":\"bubble:lucky_block\",\"placement\":[{\"type\":\"minecraft:rarity_filter\",\"chance\":" + LUCKY_CAVES[data.lucky] + "},"
								+ "{\"type\":\"minecraft:in_square\"},{\"type\":\"minecraft:height_range\",\"height\":{\"type\":\"minecraft:uniform\","
								+ "\"min_inclusive\":{\"absolute\":-54},\"max_inclusive\":{\"absolute\":40}}},"
								+ "{\"type\":\"minecraft:environment_scan\",\"direction_of_search\":\"down\",\"max_steps\":16,"
								+ "\"target_condition\":{\"type\":\"minecraft:has_sturdy_face\",\"direction\":\"up\"},"
								+ "\"allowed_search_condition\":{\"type\":\"minecraft:matching_blocks\",\"blocks\":\"minecraft:air\"}},"
								+ "{\"type\":\"minecraft:random_offset\",\"xz_spread\":0,\"y_spread\":1},"
								+ "{\"type\":\"minecraft:block_predicate_filter\",\"predicate\":{\"type\":\"minecraft:matching_blocks\",\"blocks\":\"minecraft:air\"}},"
								+ "{\"type\":\"minecraft:biome\"}]}"));
				write(dir, "data/bubble/neoforge/biome_modifier/lucky_blocks.json", JsonParser.parseString(
						"{\"type\":\"neoforge:add_features\",\"biomes\":\"#minecraft:is_overworld\","
								+ "\"features\":[\"bubble:lucky_block_surface\",\"bubble:lucky_block_caves\"],\"step\":\"top_layer_modification\"}"));
				for (String f : new String[]{"data/bubble/worldgen/configured_feature/lucky_block.json",
						"data/bubble/worldgen/placed_feature/lucky_block_surface.json", "data/bubble/worldgen/placed_feature/lucky_block_caves.json",
						"data/bubble/neoforge/biome_modifier/lucky_blocks.json"}) {
					manifest.add(f, namespaces("lucky"));
					files++;
				}
			}

			Registry<StructureSet> sets = s.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
			RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, s.registryAccess());
			for (var entry : sets.entrySet()) {
				ResourceLocation id = entry.getKey().location();
				int level = data.structures.getOrDefault(id.getNamespace(), 2);
				if (level == 2) {
					continue;
				}
				JsonElement json = StructureSet.DIRECT_CODEC.encodeStart(ops, entry.getValue()).result().orElse(null);
				if (json == null || !json.isJsonObject() || !json.getAsJsonObject().has("placement")) {
					continue;
				}
				retune(json.getAsJsonObject().getAsJsonObject("placement"), level);
				JsonArray needed = namespaces(id.getNamespace());
				if (json.getAsJsonObject().has("structures")) {
					for (JsonElement se : json.getAsJsonObject().getAsJsonArray("structures")) {
						JsonElement st = se.getAsJsonObject().get("structure");
						if (st != null && st.isJsonPrimitive()) {
							ResourceLocation sid = ResourceLocation.tryParse(st.getAsString());
							if (sid != null && !needed.contains(new com.google.gson.JsonPrimitive(sid.getNamespace()))) {
								needed.add(sid.getNamespace());
							}
						}
					}
				}
				String path = "data/" + id.getNamespace() + "/worldgen/structure_set/" + id.getPath() + ".json";
				write(dir, path, json);
				manifest.add(path, needed);
				files++;
			}
			write(dir, "manifest.json", manifest);
			Bubble.LOG.info("Pre-game: next world gets {} file(s) (lucky blocks {}, {} structure mod(s) changed)", files,
					AMOUNTS[data.lucky], data.structures.size());
		} catch (Exception e) {
			Bubble.LOG.error("Could not write the pre-game data pack", e);
		}
	}

	private static void retune(JsonObject placement, int level) {
		double mult = STRUCTURE_MULT[level];
		double freq = placement.has("frequency") ? placement.get("frequency").getAsDouble() : 1.0;
		if (level == 0) {
			placement.addProperty("frequency", 0.0);
		} else if (mult < 1) {
			placement.addProperty("frequency", freq * mult);
		} else if (placement.has("spacing") && placement.has("separation")) {
			// Twice as common = spacing / sqrt(2) (structures per area grow with 1/spacing²)
			double f = Math.sqrt(mult);
			int spacing = Math.max(2, (int) Math.round(placement.get("spacing").getAsInt() / f));
			int separation = Math.max(0, Math.min(spacing - 1, (int) Math.round(placement.get("separation").getAsInt() / f)));
			placement.addProperty("spacing", spacing);
			placement.addProperty("separation", separation);
		}
	}

	private static JsonArray namespaces(String... ns) {
		JsonArray a = new JsonArray();
		for (String n : ns) {
			a.add(n);
		}
		return a;
	}

	private static void write(Path dir, String rel, JsonElement json) throws IOException {
		Path p = dir.resolve(rel);
		Files.createDirectories(p.getParent());
		Files.writeString(p, GSON.toJson(json));
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (var walk = Files.walk(dir)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(p);
			}
		}
	}

	/**
	 * Called by WorldArchiver when a new world is about to be made: puts the staged pack into its datapacks folder,
	 * skipping files for mods that aren't installed any more (a world won't load with a broken data pack).
	 */
	static void applyStaged(Path game, Path datapacks) {
		Path staged = game.resolve(STAGED);
		Path dest = datapacks.resolve(PACK);
		try {
			deleteTree(dest);
			Path manifestFile = staged.resolve("manifest.json");
			if (!Files.exists(manifestFile)) {
				return;
			}
			JsonObject manifest = JsonParser.parseString(Files.readString(manifestFile)).getAsJsonObject();
			int copied = 0;
			for (Map.Entry<String, JsonElement> e : manifest.entrySet()) {
				boolean ok = true;
				for (JsonElement ns : e.getValue().getAsJsonArray()) {
					String n = ns.getAsString();
					ok &= n.equals("minecraft") || n.equals("bubble") || ModList.get().isLoaded(n);
				}
				Path from = staged.resolve(e.getKey());
				if (ok && Files.exists(from)) {
					Path to = dest.resolve(e.getKey());
					Files.createDirectories(to.getParent());
					Files.copy(from, to);
					copied++;
				}
			}
			if (copied > 0) {
				Files.createDirectories(dest);
				Files.copy(staged.resolve("pack.mcmeta"), dest.resolve("pack.mcmeta"));
			}
			Bubble.LOG.info("Pre-game settings: {} file(s) added to the new world", copied);
		} catch (Exception e) {
			Bubble.LOG.error("Could not apply the pre-game settings to the new world", e);
		}
	}
}
