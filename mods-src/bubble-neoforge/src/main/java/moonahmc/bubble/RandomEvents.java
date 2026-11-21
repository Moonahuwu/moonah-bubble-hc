package moonahmc.bubble;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Random events during a run: every so often (between minMinutes and maxMinutes) something happens near the group,
 * picked by weight from the enabled ones in config/bubble-events.json. Ops can switch them on and off, change how often
 * they come, and force any of them (announced or silent) from the Bubble menu's Admin tab.
 *
 * Event types:
 *   {"type":"horde", "entity":"bubble:rainbow_creeper", "count":100, "min":40, "max":64, "leave_after":300}
 *        "entities": [...] instead of "entity" mixes them; "side": true has them all come from one direction;
 *        "air": true spawns them in the sky (phantoms). Spawned mobs hunt the player they spawned around.
 *   {"type":"status", "kind":"meteor_rain", "seconds":20}      a timed wheel status (see WheelStatus)
 *   {"type":"supply_drop", "min":20, "max":40, "tables":[...]}  a loot chest lands nearby, with a beam of particles
 *   {"type":"wheel"}                                            a free spin of the mid-run wheel
 */
public final class RandomEvents {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	record Event(String id, String name, boolean enabled, int weight, JsonObject def) {
	}

	private static boolean enabled = true;
	private static boolean announce = true;
	private static int minMinutes = 10;
	private static int maxMinutes = 20;
	private static List<Event> events = new ArrayList<>();
	private static MinecraftServer server;
	/** Overworld game time of the next random event (-1 = not scheduled yet). */
	private static long nextAt = -1;
	/** supply drops: chest position and level, with the game time their beam stops. */
	private static final List<Object[]> beams = new ArrayList<>();

	private RandomEvents() {
	}

	static void init() {
		load();
		var bus = NeoForge.EVENT_BUS;
		bus.addListener((ServerStartedEvent e) -> {
			server = e.getServer();
			nextAt = -1;
			beams.clear();
		});
		bus.addListener((ServerStoppedEvent e) -> server = null);
		bus.addListener((ServerTickEvent.Post e) -> tick(e.getServer()));
	}

	// ------------------------------------------------------------------ timing

	private static long now() {
		return server == null ? 0 : server.overworld().getGameTime();
	}

	private static void schedule() {
		int lo = Math.max(1, Math.min(minMinutes, maxMinutes));
		int hi = Math.max(lo, maxMinutes);
		nextAt = now() + (lo * 1200L) + server.overworld().random.nextInt((hi - lo) * 1200 + 1);
	}

	/** Seconds until the next random event, or -1 (off, or waiting for the run to start). */
	static int secondsToNext() {
		return !enabled || nextAt < 0 || server == null ? -1 : (int) Math.max(0, (nextAt - now()) / 20);
	}

	private static void tick(MinecraftServer s) {
		if (s.getTickCount() % 10 == 0 && !beams.isEmpty()) {
			beams.removeIf(b -> now() > (long) b[2]);
			for (Object[] b : beams) {
				BlockPos pos = (BlockPos) b[0];
				ServerLevel level = (ServerLevel) b[1];
				for (int y = 1; y < 24; y += 2) {
					level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + y, pos.getZ() + 0.5, 1, 0.05, 0.4, 0.05, 0);
				}
			}
		}
		if (s.getTickCount() % 20 != 0) {
			return;
		}
		if (!enabled || Lobby.active() || s.getPlayerList().getPlayers().stream().noneMatch(BubbleServer::counts)) {
			if (Lobby.active()) {
				nextAt = -1;
			}
			return;
		}
		if (nextAt < 0) {
			schedule();
			return;
		}
		if (now() >= nextAt && !Wheel.active()) {
			schedule();
			Event ev = pickRandom();
			if (ev != null) {
				trigger(ev, announce, null);
			}
		}
	}

	private static Event pickRandom() {
		List<Event> on = events.stream().filter(e -> e.enabled() && e.weight() > 0 && possible(e)).toList();
		int total = on.stream().mapToInt(Event::weight).sum();
		if (total <= 0) {
			return null;
		}
		int r = server.overworld().random.nextInt(total);
		for (Event e : on) {
			r -= e.weight();
			if (r < 0) {
				return e;
			}
		}
		return on.get(0);
	}

	/** An ammo drop only comes up when someone playing has a gun. */
	private static boolean possible(Event e) {
		if (!"ammo_drop".equals(e.def().has("type") ? e.def().get("type").getAsString() : "")) {
			return true;
		}
		return Guns.available() && server.getPlayerList().getPlayers().stream().filter(BubbleServer::counts).anyMatch(GunSupply::armed);
	}

	// ------------------------------------------------------------------ doing an event

	/** Runs an event now. loud = tell everyone; by = the op who forced it (gets told what happened), or null. */
	static boolean trigger(Event ev, boolean loud, ServerPlayer by) {
		if (server == null) {
			return false;
		}
		List<ServerPlayer> playing = server.getPlayerList().getPlayers().stream().filter(BubbleServer::counts).toList();
		if (playing.isEmpty()) {
			if (by != null) {
				by.displayClientMessage(Component.literal("Nobody is playing (alive and in survival) to do it near.").withStyle(ChatFormatting.RED), true);
			}
			return false;
		}
		ServerPlayer target = playing.get(server.overworld().random.nextInt(playing.size()));
		JsonObject d = ev.def();
		String type = d.has("type") ? d.get("type").getAsString() : "";
		int done;
		try {
			done = switch (type) {
				case "horde" -> horde(target, d);
				case "status" -> {
					WheelStatus.start(d.get("kind").getAsString(), (d.has("seconds") ? d.get("seconds").getAsInt() : 30) * 20);
					yield 1;
				}
				case "supply_drop" -> supplyDrop(target, d, loud);
				case "ammo_drop" -> ammoDrop(playing, d, loud);
				case "wheel" -> Wheel.spinMidRun(target) == null ? 1 : 0;
				default -> {
					Bubble.LOG.warn("Random event '{}': unknown type '{}'", ev.id(), type);
					yield 0;
				}
			};
		} catch (Exception ex) {
			Bubble.LOG.error("Random event '{}' failed", ev.id(), ex);
			done = 0;
		}
		Bubble.LOG.info("Random event: {} ({}, {}) near {}: {}", ev.name(), loud ? "announced" : "silent", by == null ? "random" : "forced by " + by.getGameProfile().getName(),
				target.getGameProfile().getName(), done);
		if (done <= 0) {
			if (by != null) {
				by.displayClientMessage(Component.literal(ev.name() + ": couldn't find anywhere to do it.").withStyle(ChatFormatting.RED), true);
			}
			return false;
		}
		RunLog.add(RunLog.EVENT, ev.name() + " near " + target.getGameProfile().getName() + (loud ? "" : " (silent)")
				+ (by == null ? "" : ", forced by " + by.getGameProfile().getName()));
		if (loud) {
			String msg = d.has("message") ? d.get("message").getAsString() : "";
			server.getPlayerList().broadcastSystemMessage(Component.literal("⚠ ").withStyle(ChatFormatting.RED)
					.append(Component.literal(ev.name()).withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
					.append(Component.literal(msg.isEmpty() ? "" : "  " + msg).withStyle(ChatFormatting.GRAY)), false);
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				BubbleServer.title(p, Component.literal(ev.name()).withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
						Component.literal(msg).withStyle(ChatFormatting.GRAY), 5, 50, 15);
				p.playNotifySound(SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 0.7f, 1f);
			}
		} else if (by != null) {
			by.displayClientMessage(Component.literal("Done quietly: " + ev.name() + " near " + target.getGameProfile().getName()).withStyle(ChatFormatting.GRAY), true);
		}
		return true;
	}

	/** A pack of mobs `min`-`max` blocks out from the player. Returns how many spawned. */
	private static int horde(ServerPlayer p, JsonObject d) {
		ServerLevel level = p.serverLevel();
		RandomSource r = level.random;
		List<EntityType<?>> types = new ArrayList<>();
		if (d.has("entities")) {
			d.getAsJsonArray("entities").forEach(x -> EntityType.byString(x.getAsString()).ifPresent(types::add));
		} else if (d.has("entity")) {
			EntityType.byString(d.get("entity").getAsString()).ifPresent(types::add);
		}
		if (types.isEmpty()) {
			return 0;
		}
		int count = d.has("count") ? d.get("count").getAsInt() : 10;
		double min = d.has("min") ? d.get("min").getAsDouble() : 32;
		double max = Math.max(min + 1, d.has("max") ? d.get("max").getAsDouble() : 56);
		boolean side = d.has("side") && d.get("side").getAsBoolean();
		boolean air = d.has("air") && d.get("air").getAsBoolean();
		int leave = d.has("leave_after") ? d.get("leave_after").getAsInt() : 0;
		double sideAngle = r.nextDouble() * Math.PI * 2;
		int spawned = 0;
		for (int i = 0; i < count; i++) {
			EntityType<?> type = types.get(r.nextInt(types.size()));
			for (int tries = 0; tries < 10; tries++) {
				double a = side ? sideAngle + (r.nextDouble() - 0.5) * 0.9 : r.nextDouble() * Math.PI * 2;
				double dist = min + r.nextDouble() * (max - min);
				int x = (int) Math.floor(p.getX() + Math.cos(a) * dist);
				int z = (int) Math.floor(p.getZ() + Math.sin(a) * dist);
				BlockPos pos = air ? BlockPos.containing(x, Math.min(p.getY() + 20 + r.nextInt(8), level.getMaxBuildHeight() - 4), z) : ground(level, x, z, p.getBlockY());
				if (pos == null || !level.hasChunkAt(pos)) {
					continue;
				}
				Entity e = type.spawn(level, pos, MobSpawnType.EVENT);
				if (e == null) {
					continue;
				}
				if (e instanceof Mob mob) {
					var follow = mob.getAttribute(Attributes.FOLLOW_RANGE);
					if (follow != null) {
						follow.addOrUpdateTransientModifier(new AttributeModifier(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "event_hunt"), max + 16,
								AttributeModifier.Operation.ADD_VALUE));
					}
					if (!(e instanceof Watcher)) {
						mob.setTarget(p);
					}
				}
				WheelStatus.markLeaving(e, leave);
				spawned++;
				break;
			}
		}
		return spawned;
	}

	/** Somewhere a mob can stand at (x, z): on the surface if the player is near it, otherwise near the player's height. */
	private static BlockPos ground(ServerLevel level, int x, int z, int nearY) {
		if (!level.hasChunkAt(new BlockPos(x, nearY, z))) {
			return null;
		}
		if (!level.dimensionType().hasCeiling()) {
			int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			if (Math.abs(top - nearY) <= 12 && standable(level, new BlockPos(x, top, z))) {
				return new BlockPos(x, top, z);
			}
		}
		for (int dy = 16; dy >= -16; dy--) {
			BlockPos pos = new BlockPos(x, nearY + dy, z);
			if (standable(level, pos)) {
				return pos;
			}
		}
		return null;
	}

	private static boolean standable(ServerLevel level, BlockPos pos) {
		BlockPos below = pos.below();
		return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP) && level.getFluidState(below).isEmpty()
				&& level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getFluidState(pos).isEmpty()
				&& level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
	}

	/** A chest of loot nearby with a beam over it; announced events say which way it is. */
	private static int supplyDrop(ServerPlayer p, JsonObject d, boolean loud) {
		ServerLevel level = p.serverLevel();
		RandomSource r = level.random;
		List<String> tables = new ArrayList<>();
		if (d.has("tables")) {
			d.getAsJsonArray("tables").forEach(x -> tables.add(x.getAsString()));
		}
		if (tables.isEmpty()) {
			tables.add("minecraft:chests/simple_dungeon");
		}
		double min = d.has("min") ? d.get("min").getAsDouble() : 20;
		double max = Math.max(min + 1, d.has("max") ? d.get("max").getAsDouble() : 40);
		for (int tries = 0; tries < 20; tries++) {
			double a = r.nextDouble() * Math.PI * 2;
			double dist = min + r.nextDouble() * (max - min);
			BlockPos pos = ground(level, (int) Math.floor(p.getX() + Math.cos(a) * dist), (int) Math.floor(p.getZ() + Math.sin(a) * dist), p.getBlockY());
			if (pos == null) {
				continue;
			}
			level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
			if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
				chest.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse(tables.get(r.nextInt(tables.size())))), r.nextLong());
			}
			level.playSound(null, pos, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.BLOCKS, 2f, 0.8f);
			beams.add(new Object[] {pos.immutable(), level, now() + 20 * 120});
			if (loud) {
				String dir = Direction.fromYRot(Math.toDegrees(Math.atan2(-(pos.getX() - p.getX()), pos.getZ() - p.getZ()))).getName();
				server.getPlayerList().broadcastSystemMessage(Component.literal("📦 The supply drop landed " + (int) Math.sqrt(p.blockPosition().distSqr(pos))
						+ " blocks " + dir + " of " + p.getGameProfile().getName() + " (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "). Follow the beam!")
						.withStyle(ChatFormatting.AQUA), false);
			}
			return 1;
		}
		return 0;
	}

	/**
	 * A crate of ammo near everyone who has a gun, for the guns they're carrying ("magazines" each, 4 by default),
	 * with a beam over it. Returns how many crates landed.
	 */
	private static int ammoDrop(List<ServerPlayer> playing, JsonObject d, boolean loud) {
		if (!Guns.available()) {
			return 0;
		}
		int mags = d.has("magazines") ? Math.max(1, d.get("magazines").getAsInt()) : 4;
		double min = d.has("min") ? d.get("min").getAsDouble() : 6;
		double max = Math.max(min + 1, d.has("max") ? d.get("max").getAsDouble() : 14);
		int landed = 0;
		for (ServerPlayer p : playing) {
			List<ItemStack> ammo = GunSupply.ammoFor(p, mags);
			if (ammo.isEmpty()) {
				continue;
			}
			ServerLevel level = p.serverLevel();
			RandomSource r = level.random;
			for (int tries = 0; tries < 20; tries++) {
				double a = r.nextDouble() * Math.PI * 2;
				double dist = min + r.nextDouble() * (max - min);
				BlockPos pos = ground(level, (int) Math.floor(p.getX() + Math.cos(a) * dist), (int) Math.floor(p.getZ() + Math.sin(a) * dist), p.getBlockY());
				if (pos == null) {
					continue;
				}
				level.setBlock(pos, Blocks.BARREL.defaultBlockState().setValue(net.minecraft.world.level.block.BarrelBlock.FACING, Direction.UP), 3);
				if (level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity barrel) {
					for (int i = 0; i < ammo.size() && i < barrel.getContainerSize(); i++) {
						barrel.setItem(i, ammo.get(i));
					}
				}
				level.playSound(null, pos, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.BLOCKS, 2f, 1.2f);
				beams.add(new Object[] {pos.immutable(), level, now() + 20 * 120});
				p.sendSystemMessage(Component.literal("🔫 Your ammo crate (a barrel) landed " + (int) Math.sqrt(p.blockPosition().distSqr(pos)) + " blocks away at "
						+ pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ". Follow the beam!").withStyle(ChatFormatting.GOLD));
				landed++;
				break;
			}
		}
		return landed;
	}

	// ------------------------------------------------------------------ admin editor

	static JsonObject editorJson() {
		JsonObject o = new JsonObject();
		o.addProperty("enabled", enabled);
		o.addProperty("announce", announce);
		o.addProperty("min", minMinutes);
		o.addProperty("max", maxMinutes);
		o.addProperty("next", secondsToNext());
		JsonArray arr = new JsonArray();
		for (Event e : events) {
			JsonObject eo = new JsonObject();
			eo.addProperty("id", e.id());
			eo.addProperty("name", e.name());
			eo.addProperty("enabled", e.enabled());
			eo.addProperty("weight", e.weight());
			arr.add(eo);
		}
		o.add("list", arr);
		return o;
	}

	/** what: enabled / announce / min / max (whole config), or event_enabled / event_weight (one event). */
	static void edit(String what, int index, int value) {
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
			switch (what) {
				case "enabled" -> root.addProperty("enabled", value != 0);
				case "announce" -> root.addProperty("announce", value != 0);
				case "min" -> root.addProperty("minMinutes", Math.max(1, Math.min(240, value)));
				case "max" -> root.addProperty("maxMinutes", Math.max(1, Math.min(240, value)));
				case "event_enabled", "event_weight" -> {
					JsonArray arr = root.getAsJsonArray("events");
					if (index < 0 || index >= arr.size()) {
						return;
					}
					JsonObject eo = arr.get(index).getAsJsonObject();
					if (what.equals("event_enabled")) {
						eo.addProperty("enabled", value != 0);
					} else {
						eo.addProperty("weight", Math.max(0, Math.min(99, value)));
					}
				}
				default -> {
					return;
				}
			}
			Files.writeString(file(), GSON.toJson(root));
			int oldMin = minMinutes, oldMax = maxMinutes;
			load();
			if ((what.equals("min") || what.equals("max")) && (oldMin != minMinutes || oldMax != maxMinutes) && nextAt >= 0 && server != null) {
				schedule();
			}
		} catch (Exception e) {
			Bubble.LOG.error("Could not edit config/bubble-events.json", e);
		}
	}

	static boolean force(int index, boolean loud, ServerPlayer by) {
		if (index == -1) {
			Event ev = pickRandom();
			return ev != null && trigger(ev, loud, by);
		}
		return index >= 0 && index < events.size() && trigger(events.get(index), loud, by);
	}

	/** An event's name for the admin pop-ups ("a random event" for -1). */
	static String name(int index) {
		return index >= 0 && index < events.size() ? events.get(index).name() : "A random event";
	}

	static boolean enabled() {
		return enabled;
	}

	static List<String> ids() {
		return events.stream().map(Event::id).toList();
	}

	static int indexOf(String id) {
		for (int i = 0; i < events.size(); i++) {
			if (events.get(i).id().equalsIgnoreCase(id)) {
				return i;
			}
		}
		return -2;
	}

	// ------------------------------------------------------------------ file

	private static Path file() {
		return FMLPaths.CONFIGDIR.get().resolve("bubble-events.json");
	}

	static void load() {
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
			enabled = !root.has("enabled") || root.get("enabled").getAsBoolean();
			announce = !root.has("announce") || root.get("announce").getAsBoolean();
			minMinutes = root.has("minMinutes") ? root.get("minMinutes").getAsInt() : 10;
			maxMinutes = root.has("maxMinutes") ? root.get("maxMinutes").getAsInt() : 20;
			List<Event> out = new ArrayList<>();
			for (JsonElement el : root.getAsJsonArray("events")) {
				JsonObject eo = el.getAsJsonObject();
				out.add(new Event(eo.get("id").getAsString(), eo.has("name") ? eo.get("name").getAsString() : eo.get("id").getAsString(),
						!eo.has("enabled") || eo.get("enabled").getAsBoolean(), eo.has("weight") ? Math.max(0, eo.get("weight").getAsInt()) : 1, eo));
			}
			events = out;
		} catch (Exception e) {
			Bubble.LOG.error("Could not read config/bubble-events.json", e);
		}
	}

	/** Events added in a newer version get added to an existing file once (a deleted one stays deleted). */
	private static boolean addNewDefaults(JsonObject root) {
		if (!root.has("events")) {
			root.add("events", new JsonArray());
		}
		JsonArray seen = root.has("defaultsSeen") ? root.getAsJsonArray("defaultsSeen") : new JsonArray();
		if (!root.has("defaultsSeen")) {
			root.getAsJsonArray("events").forEach(e -> seen.add(e.getAsJsonObject().get("id").getAsString()));
			root.add("defaultsSeen", seen);
		}
		boolean changed = false;
		for (JsonElement d : defaults().getAsJsonArray("events")) {
			String id = d.getAsJsonObject().get("id").getAsString();
			if (!seen.contains(new com.google.gson.JsonPrimitive(id))) {
				seen.add(id);
				root.getAsJsonArray("events").add(d);
				changed = true;
			}
		}
		return changed;
	}

	private static JsonObject defaults() {
		JsonObject root = JsonParser.parseString("""
				{
				  "enabled": true,
				  "announce": true,
				  "minMinutes": 10,
				  "maxMinutes": 20,
				  "events": [
				    {"id": "rainbow_horde", "name": "Rainbow creeper horde", "weight": 2, "type": "horde", "entity": "bubble:rainbow_creeper",
				     "count": 100, "min": 40, "max": 64, "leave_after": 300, "message": "Something colourful is coming. Lots of it."},
				    {"id": "watcher", "name": "The Watcher", "weight": 3, "type": "horde", "entity": "bubble:watcher", "count": 1, "min": 16, "max": 28,
				     "leave_after": 600, "message": "Something is watching you..."},
				    {"id": "evil_watcher", "name": "The Evil Watcher", "weight": 1, "type": "horde", "entity": "bubble:evil_watcher", "count": 1,
				     "min": 24, "max": 36, "leave_after": 300, "message": "Don't look at it. Not even through walls."},
				    {"id": "zombie_siege", "name": "Zombie siege", "weight": 3, "type": "horde", "entities": ["minecraft:zombie", "minecraft:zombie", "minecraft:husk"],
				     "count": 30, "min": 28, "max": 40, "side": true, "leave_after": 600, "message": "A wall of zombies is coming from one side."},
				    {"id": "gatling_squad", "name": "Gatling squad", "weight": 2, "type": "horde", "entity": "bubble:gatling_skeleton", "count": 4,
				     "min": 22, "max": 34, "leave_after": 400, "message": "Take cover."},
				    {"id": "swarm", "name": "The swarm", "weight": 2, "type": "horde", "entity": "bubble:swarm_zombie", "count": 1, "min": 16, "max": 24,
				     "leave_after": 600, "message": "Don't let it hit you."},
				    {"id": "jockeys", "name": "Jockey jockeys", "weight": 2, "type": "horde", "entity": "bubble:jockey_jockey", "count": 2, "min": 18, "max": 30,
				     "leave_after": 600, "message": "Yes, that's a chicken riding a spider riding..."},
				    {"id": "nuke_creeper", "name": "Nuke creeper", "weight": 1, "type": "horde", "entity": "bubble:nuke_creeper", "count": 1, "min": 30, "max": 48,
				     "leave_after": 300, "message": "Listen for the hiss. Run."},
				    {"id": "pillager_patrol", "name": "Pillager raid party", "weight": 2, "type": "horde",
				     "entities": ["minecraft:pillager", "minecraft:pillager", "minecraft:vindicator", "minecraft:pillager", "minecraft:ravager"],
				     "count": 9, "min": 30, "max": 44, "side": true, "leave_after": 600, "message": "They've got a ravager."},
				    {"id": "phantoms", "name": "Phantom flock", "weight": 2, "type": "horde", "entity": "minecraft:phantom", "count": 8, "min": 8, "max": 20,
				     "air": true, "leave_after": 300, "message": "Look up."},
				    {"id": "meteor_shower", "name": "Meteor shower", "weight": 2, "type": "status", "kind": "meteor_rain", "seconds": 20, "message": "Watch the sky."},
				    {"id": "lightning_storm", "name": "Lightning storm", "weight": 2, "type": "status", "kind": "lightning", "seconds": 30, "message": "Stay off the hills."},
				    {"id": "supply_drop", "name": "Supply drop", "weight": 3, "type": "supply_drop", "min": 20, "max": 40, "message": "Loot landed nearby.",
				     "tables": ["minecraft:chests/simple_dungeon", "minecraft:chests/buried_treasure", "minecraft:chests/bastion_treasure", "minecraft:chests/end_city_treasure", "minecraft:chests/ancient_city"]},
				    {"id": "free_spin", "name": "Free Wheel Spin", "weight": 2, "type": "wheel", "message": "The wheel spins itself."},
				    {"id": "ammo_drop", "name": "Ammo drop", "weight": 3, "type": "ammo_drop", "magazines": 4, "min": 6, "max": 14,
				     "message": "Crates of ammo for everyone with a gun. Follow the beams."}
				  ]
				}""").getAsJsonObject();
		return root;
	}
}
