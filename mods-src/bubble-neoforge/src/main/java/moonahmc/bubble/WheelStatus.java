package moonahmc.bubble;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Timed statuses from the mid-run wheel. Each one is on for everyone for a set time, shows a countdown bar at the top
 * of the screen, and switches itself off when the time is up (or with /bubble wheel clear).
 *
 * Done here on the server: meteor_rain, tnt_rain, lightning, random_gravity, floor_is_lava (blocks are put back
 * afterwards), butterfingers, midas, flight, double_matter, size (everyone is randomly tiny or giant).
 * Client ones (upside_down, ice) are done by the client (Fx); they only get the countdown bar from here.
 */
public final class WheelStatus {
	record Def(String title, BossEvent.BossBarColor color) {
	}

	static final Map<String, Def> DEFS = new LinkedHashMap<>();

	static {
		DEFS.put("meteor_rain", new Def("☄ Meteor rain", BossEvent.BossBarColor.RED));
		DEFS.put("tnt_rain", new Def("💣 TNT rain", BossEvent.BossBarColor.RED));
		DEFS.put("lightning", new Def("⚡ Lightning storm", BossEvent.BossBarColor.YELLOW));
		DEFS.put("random_gravity", new Def("🌀 Random gravity", BossEvent.BossBarColor.PURPLE));
		DEFS.put("floor_is_lava", new Def("🔥 The floor is lava", BossEvent.BossBarColor.RED));
		DEFS.put("butterfingers", new Def("🧈 Butterfingers", BossEvent.BossBarColor.YELLOW));
		DEFS.put("midas", new Def("✨ Midas touch", BossEvent.BossBarColor.YELLOW));
		DEFS.put("flight", new Def("🕊 Flight", BossEvent.BossBarColor.BLUE));
		DEFS.put("double_matter", new Def("💎 Double Strange Matter", BossEvent.BossBarColor.GREEN));
		DEFS.put("size", new Def("📏 Tiny or giant", BossEvent.BossBarColor.GREEN));
		DEFS.put("upside_down", new Def("🙃 Upside down", BossEvent.BossBarColor.PURPLE));
		DEFS.put("ice", new Def("🧊 Ice ground", BossEvent.BossBarColor.BLUE));
		DEFS.put("fragile", new Def("🦴 Fragile mode", BossEvent.BossBarColor.WHITE));
		DEFS.put("ragdoll_roulette", new Def("🎲 Ragdoll roulette", BossEvent.BossBarColor.PINK));
	}

	private static final int MAX_LAVA_BLOCKS = 6000;
	private static MinecraftServer server;
	private static final Map<String, Long> until = new HashMap<>();
	private static final Map<String, Long> started = new HashMap<>();
	private static final Map<String, ServerBossEvent> bars = new HashMap<>();
	/** floor_is_lava: what each changed block was, so it can be put back. */
	private static final Map<ResourceKey<Level>, Map<BlockPos, BlockState>> lava = new HashMap<>();
	/** size: true = giant, false = tiny, per player. */
	private static final Map<UUID, Boolean> giant = new HashMap<>();
	/** random_gravity: 0 heavy, 1 floaty, 2 upwards (levitation, then floaty). */
	private static int gravityMode = -1;

	private WheelStatus() {
	}

	static void init() {
		var bus = NeoForge.EVENT_BUS;
		bus.addListener((ServerStartedEvent e) -> {
			server = e.getServer();
			until.clear();
			started.clear();
			bars.clear();
			giant.clear();
			lava.clear();
		});
		bus.addListener((ServerStoppingEvent e) -> {
			restoreLava();
			server = null;
		});
		bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
			if (e.getEntity() instanceof ServerPlayer p) {
				bars.values().forEach(b -> b.addPlayer(p));
			}
		});
		bus.addListener((ServerTickEvent.Post e) -> tick(e.getServer()));
		bus.addListener((LivingDamageEvent.Post e) -> {
			if (isOn("midas") && e.getSource().getEntity() instanceof Player p && !(e.getEntity() instanceof Player)
					&& e.getEntity().level() instanceof ServerLevel level) {
				var at = e.getEntity().position().add(0, e.getEntity().getBbHeight() / 2, 0);
				RandomSource r = level.random;
				ItemStack gold = r.nextInt(8) == 0 ? new ItemStack(Items.GOLD_INGOT) : new ItemStack(Items.GOLD_NUGGET, 1 + r.nextInt(3));
				ItemEntity drop = new ItemEntity(level, at.x, at.y, at.z, gold);
				drop.setDeltaMovement((r.nextDouble() - 0.5) * 0.3, 0.3, (r.nextDouble() - 0.5) * 0.3);
				level.addFreshEntity(drop);
				level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 1.6f);
			}
		});
	}

	public static boolean isOn(String kind) {
		return server != null && until.getOrDefault(kind, 0L) > server.getTickCount();
	}

	/** Strange Matter earned now is doubled (the "Double Strange Matter" slice). */
	public static boolean doubleMatter() {
		return isOn("double_matter");
	}

	static List<String> active() {
		List<String> out = new ArrayList<>();
		if (server != null) {
			until.forEach((k, t) -> {
				if (t > server.getTickCount()) {
					out.add(k);
				}
			});
		}
		return out;
	}

	/** Turns `kind` on for everyone for `ticks` (a second one while it's on adds the time). */
	static void start(String kind, int ticks) {
		if (server == null || ticks <= 0) {
			return;
		}
		Def def = DEFS.get(kind);
		if (def == null) {
			Bubble.LOG.warn("Wheel: unknown status '{}'", kind);
			return;
		}
		long now = server.getTickCount();
		boolean was = isOn(kind);
		long end = (was ? until.get(kind) : now) + ticks;
		until.put(kind, end);
		if (!was) {
			started.put(kind, now);
			ServerBossEvent bar = new ServerBossEvent(Component.literal(def.title()), def.color(), BossEvent.BossBarOverlay.PROGRESS);
			server.getPlayerList().getPlayers().forEach(bar::addPlayer);
			bars.put(kind, bar);
			begin(kind);
		}
		updateBar(kind, now);
	}

	/** Turns `kind` off now. Returns whether it was on. */
	static boolean stop(String kind) {
		Long end = until.remove(kind);
		started.remove(kind);
		ServerBossEvent bar = bars.remove(kind);
		if (bar != null) {
			bar.removeAllPlayers();
		}
		if (end == null || server == null) {
			return false;
		}
		finish(kind);
		return end > server.getTickCount();
	}

	private static void begin(String kind) {
		switch (kind) {
			case "random_gravity" -> gravityMode = -1;
			case "size" -> giant.clear();
			case "fragile" -> {
				RagdollTuning.fragile(true);
				RagdollTuning.announce(true);
			}
			default -> {
			}
		}
	}

	private static void finish(String kind) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		switch (kind) {
			case "floor_is_lava" -> restoreLava();
			case "fragile" -> RagdollTuning.fragile(false); // "wore off" is said by tick()
			case "random_gravity" -> players.forEach(p -> gravity(p, -1));
			case "flight" -> players.forEach(p -> {
				boolean wasFlying = p.getAbilities().flying && !p.isCreative() && !p.isSpectator();
				modifier(p, NeoForgeMod.CREATIVE_FLIGHT, "wheel_flight", 0, false);
				if (wasFlying) {
					p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 20));
				}
			});
			case "size" -> {
				players.forEach(p -> size(p, null));
				giant.clear();
			}
			default -> {
			}
		}
	}

	private static void updateBar(String kind, long now) {
		ServerBossEvent bar = bars.get(kind);
		Long end = until.get(kind);
		Long from = started.get(kind);
		if (bar == null || end == null || from == null) {
			return;
		}
		long left = Math.max(0, end - now);
		bar.setProgress(Math.max(0f, Math.min(1f, left / (float) Math.max(1, end - from))));
		long s = (left + 19) / 20;
		String extra = kind.equals("random_gravity") && gravityMode >= 0 ? "  (" + new String[] {"heavy", "floaty", "up you go"}[gravityMode] + ")" : "";
		bar.setName(Component.literal(DEFS.get(kind).title() + extra + "  " + (s / 60) + ":" + String.format("%02d", s % 60)));
	}

	// ------------------------------------------------------------------ ticking

	private static void tick(MinecraftServer s) {
		if (until.isEmpty()) {
			return;
		}
		long now = s.getTickCount();
		if (Lobby.active()) {
			new ArrayList<>(until.keySet()).forEach(WheelStatus::stop);
			return;
		}
		for (String kind : new ArrayList<>(until.keySet())) {
			if (until.get(kind) <= now) {
				stop(kind);
				s.getPlayerList().broadcastSystemMessage(Component.literal("🎡 " + DEFS.get(kind).title() + " wore off.").withStyle(ChatFormatting.GRAY), false);
				continue;
			}
			if (now % 10 == 0) {
				updateBar(kind, now);
			}
			long age = now - started.getOrDefault(kind, now);
			for (ServerPlayer p : s.getPlayerList().getPlayers()) {
				if (BubbleServer.counts(p)) {
					try {
						tick(kind, p, age);
					} catch (Exception ex) {
						Bubble.LOG.error("Wheel status {} failed for {}", kind, p.getGameProfile().getName(), ex);
					}
				}
			}
			if (kind.equals("random_gravity") && age % 200 == 0) {
				gravityMode = s.overworld().random.nextInt(3);
				String what = new String[] {"Gravity just got heavy!", "Gravity's gone floaty!", "Up you go!"}[gravityMode];
				for (ServerPlayer p : s.getPlayerList().getPlayers()) {
					p.displayClientMessage(Component.literal("🌀 " + what).withStyle(ChatFormatting.LIGHT_PURPLE), true);
					if (BubbleServer.counts(p) && gravityMode == 2) {
						p.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 50, 1));
					}
				}
				updateBar(kind, now);
			}
		}
	}

	/** Ragdoll roulette: the last age someone was picked (so it's once per round, not once per player). */
	private static long roulettePicked = -1;

	private static void tick(String kind, ServerPlayer p, long age) {
		ServerLevel level = p.serverLevel();
		RandomSource r = p.getRandom();
		switch (kind) {
			case "ragdoll_roulette" -> {
				// every 20 seconds (from 5 s in) one random player goes limp for 5 seconds
				if (age % 400 == 100 && roulettePicked != age) {
					roulettePicked = age;
					List<ServerPlayer> pool = server.getPlayerList().getPlayers().stream().filter(BubbleServer::counts).toList();
					if (!pool.isEmpty()) {
						ServerPlayer pick = pool.get(r.nextInt(pool.size()));
						server.getPlayerList().broadcastSystemMessage(Component.literal("🎲 ").withStyle(ChatFormatting.LIGHT_PURPLE)
								.append(pick.getDisplayName()).append(Component.literal(" goes limp!").withStyle(ChatFormatting.LIGHT_PURPLE)), false);
						Ragdolls.toss(pick, 100);
					}
				}
			}
			case "meteor_rain" -> {
				if (age % 25 == 0 && !level.dimensionType().hasCeiling()) {
					Vec3 at = around(p, 4, 22);
					LargeFireball ball = EntityType.FIREBALL.create(level);
					if (ball != null) {
						ball.moveTo(at.x, Math.min(p.getY() + 40, level.getMaxBuildHeight() - 2), at.z);
						ball.setDeltaMovement((r.nextDouble() - 0.5) * 0.4, -1.4, (r.nextDouble() - 0.5) * 0.4);
						level.addFreshEntity(ball);
					}
				}
			}
			case "tnt_rain" -> {
				if (age % 40 == 0) {
					Vec3 at = around(p, 5, 14);
					double y = level.dimensionType().hasCeiling() ? p.getY() + 6 : Math.min(p.getY() + 20, level.getMaxBuildHeight() - 2);
					PrimedTnt tnt = new PrimedTnt(level, at.x, y, at.z, null);
					tnt.setFuse(80);
					level.addFreshEntity(tnt);
				}
			}
			case "lightning" -> {
				if (age % 40 == 0) {
					Vec3 at = around(p, 3, 12);
					LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
					if (bolt != null) {
						bolt.moveTo(at.x, ground(level, at, p.getY()), at.z);
						level.addFreshEntity(bolt);
					}
				}
			}
			case "random_gravity" -> {
				if (gravityMode >= 0) {
					gravity(p, gravityMode);
				}
			}
			case "floor_is_lava" -> {
				if (age % 4 == 0 && p.onGround()) {
					lavaUnder(p);
				}
			}
			case "butterfingers" -> {
				if (r.nextInt(220) == 0 && !p.getMainHandItem().isEmpty() && !p.isUsingItem()) {
					if (p.drop(true)) {
						p.displayClientMessage(Component.literal("🧈 Butterfingers!").withStyle(ChatFormatting.YELLOW), true);
						level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1f, 0.5f);
					}
				}
			}
			case "flight" -> {
				modifier(p, NeoForgeMod.CREATIVE_FLIGHT, "wheel_flight", 1, true);
				long left = until.get(kind) - p.server.getTickCount();
				if (left == 100) {
					p.displayClientMessage(Component.literal("🕊 Flight ends in 5 seconds, find somewhere to land!").withStyle(ChatFormatting.AQUA), true);
				}
			}
			case "size" -> size(p, giant.computeIfAbsent(p.getUUID(), u -> r.nextBoolean()));
			default -> {
			}
		}
	}

	/** A random spot on the ground `min`-`max` blocks from the player. */
	private static Vec3 around(ServerPlayer p, double min, double max) {
		RandomSource r = p.getRandom();
		double a = r.nextDouble() * Math.PI * 2;
		double d = min + r.nextDouble() * (max - min);
		return new Vec3(p.getX() + Math.cos(a) * d, p.getY(), p.getZ() + Math.sin(a) * d);
	}

	private static double ground(ServerLevel level, Vec3 at, double fallback) {
		if (level.dimensionType().hasCeiling()) {
			return fallback;
		}
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(at.x), (int) Math.floor(at.z));
	}

	private static void modifier(ServerPlayer p, Holder<Attribute> attr, String key, double amount, boolean on) {
		var inst = p.getAttribute(attr);
		if (inst == null) {
			return;
		}
		ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, key);
		AttributeModifier.Operation op = attr == NeoForgeMod.CREATIVE_FLIGHT ? AttributeModifier.Operation.ADD_VALUE : AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
		if (!on) {
			inst.removeModifier(rl);
			return;
		}
		AttributeModifier current = inst.getModifier(rl);
		if (current == null || current.amount() != amount) {
			inst.addOrUpdateTransientModifier(new AttributeModifier(rl, amount, op));
		}
	}

	/** -1 = back to normal. */
	private static void gravity(ServerPlayer p, int mode) {
		modifier(p, Attributes.GRAVITY, "wheel_gravity", switch (mode) {
			case 0 -> 1.5;
			case 1, 2 -> -0.8;
			default -> 0;
		}, mode >= 0);
		var fall = p.getAttribute(Attributes.SAFE_FALL_DISTANCE);
		if (fall != null) {
			ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "wheel_gravity_fall");
			if (mode >= 0) {
				if (fall.getModifier(rl) == null) {
					fall.addOrUpdateTransientModifier(new AttributeModifier(rl, 20, AttributeModifier.Operation.ADD_VALUE));
				}
			} else {
				fall.removeModifier(rl);
			}
		}
	}

	/** null = back to normal size. */
	private static void size(ServerPlayer p, Boolean big) {
		modifier(p, Attributes.SCALE, "wheel_size", big == null ? 0 : big ? 1.0 : -0.6, big != null);
		modifier(p, Attributes.BLOCK_INTERACTION_RANGE, "wheel_size_reach", 0.5, big != null && big);
		modifier(p, Attributes.ENTITY_INTERACTION_RANGE, "wheel_size_reach2", 0.5, big != null && big);
		modifier(p, Attributes.STEP_HEIGHT, "wheel_size_step", 1.0, big != null && big);
	}

	// ------------------------------------------------------------------ floor is lava

	private static void lavaUnder(ServerPlayer p) {
		ServerLevel level = p.serverLevel();
		Map<BlockPos, BlockState> changed = lava.computeIfAbsent(level.dimension(), k -> new HashMap<>());
		if (lava.values().stream().mapToInt(Map::size).sum() >= MAX_LAVA_BLOCKS) {
			return;
		}
		BlockPos pos = p.getOnPos();
		if (changed.containsKey(pos)) {
			return;
		}
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || state.is(Blocks.MAGMA_BLOCK) || state.hasBlockEntity() || state.getDestroySpeed(level, pos) < 0
				|| !state.isCollisionShapeFullBlock(level, pos)) {
			return;
		}
		changed.put(pos.immutable(), state);
		level.setBlock(pos, Blocks.MAGMA_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
		level.sendParticles(ParticleTypes.LAVA, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 3, 0.3, 0.05, 0.3, 0);
	}

	/** Puts every block floor_is_lava changed back (unless something else has replaced the magma since). */
	private static void restoreLava() {
		if (server == null) {
			lava.clear();
			return;
		}
		int n = 0;
		for (var e : lava.entrySet()) {
			ServerLevel level = server.getLevel(e.getKey());
			if (level == null) {
				continue;
			}
			for (var b : e.getValue().entrySet()) {
				if (level.getBlockState(b.getKey()).is(Blocks.MAGMA_BLOCK)) {
					level.setBlock(b.getKey(), b.getValue(), Block.UPDATE_CLIENTS);
					n++;
				}
			}
		}
		lava.clear();
		if (n > 0) {
			Bubble.LOG.info("Floor is lava: put back {} blocks", n);
		}
	}

	// ------------------------------------------------------------------ one-off actions (from Wheel)

	/** Mixes up the player's hotbar. */
	static void shuffleHotbar(ServerPlayer p) {
		var inv = p.getInventory();
		List<ItemStack> bar = new ArrayList<>();
		for (int i = 0; i < 9; i++) {
			bar.add(inv.getItem(i));
		}
		java.util.Collections.shuffle(bar, new java.util.Random(p.getRandom().nextLong()));
		for (int i = 0; i < 9; i++) {
			inv.setItem(i, bar.get(i));
		}
		p.containerMenu.broadcastChanges();
		p.displayClientMessage(Component.literal("🔀 Your hotbar got shuffled!").withStyle(ChatFormatting.LIGHT_PURPLE), true);
	}

	/** Everyone's hotbar moves on to the next player (round the circle). With one player, it's just shuffled. */
	static void swapHotbars(List<ServerPlayer> players) {
		List<ServerPlayer> ring = new ArrayList<>(players.stream().filter(BubbleServer::counts).toList());
		if (ring.size() < 2) {
			ring.forEach(WheelStatus::shuffleHotbar);
			return;
		}
		java.util.Collections.shuffle(ring, new java.util.Random(server.overworld().random.nextLong()));
		List<List<ItemStack>> bars = new ArrayList<>();
		for (ServerPlayer p : ring) {
			List<ItemStack> bar = new ArrayList<>();
			for (int i = 0; i < 9; i++) {
				bar.add(p.getInventory().getItem(i));
			}
			bars.add(bar);
		}
		for (int k = 0; k < ring.size(); k++) {
			ServerPlayer to = ring.get(k);
			ServerPlayer from = ring.get((k + ring.size() - 1) % ring.size());
			List<ItemStack> bar = bars.get((k + ring.size() - 1) % ring.size());
			for (int i = 0; i < 9; i++) {
				to.getInventory().setItem(i, bar.get(i));
			}
			to.containerMenu.broadcastChanges();
			to.displayClientMessage(Component.literal("🔄 You got " + from.getGameProfile().getName() + "'s hotbar!").withStyle(ChatFormatting.LIGHT_PURPLE), true);
		}
	}

	static void heal(ServerPlayer p) {
		if (!p.isAlive()) {
			return;
		}
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(20);
		p.getFoodData().setSaturation(20f);
		p.clearFire();
		p.serverLevel().sendParticles(ParticleTypes.HEART, p.getX(), p.getY() + 1.8, p.getZ(), 6, 0.4, 0.3, 0.4, 0);
	}

	/** Somewhere safe on the ground up to `radius` blocks away. */
	static void teleport(ServerPlayer p, int radius) {
		if (!BubbleServer.counts(p)) {
			return;
		}
		ServerLevel level = p.serverLevel();
		RandomSource r = p.getRandom();
		double fromX = p.getX(), fromY = p.getY(), fromZ = p.getZ();
		p.stopRiding();
		for (int tries = 0; tries < 24; tries++) {
			double a = r.nextDouble() * Math.PI * 2;
			double d = 10 + r.nextDouble() * Math.max(1, radius - 10);
			double x = Math.floor(p.getX() + Math.cos(a) * d) + 0.5;
			double z = Math.floor(p.getZ() + Math.sin(a) * d) + 0.5;
			double y = level.dimensionType().hasCeiling() ? p.getY() + r.nextInt(17) - 8
					: level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
			BlockPos below = BlockPos.containing(x, y - 1, z);
			if (!level.dimensionType().hasCeiling() && !level.getFluidState(below).isEmpty()) {
				continue; // not into the sea / a lava lake
			}
			if (p.randomTeleport(x, y, z, true)) {
				p.resetFallDistance();
				level.playSound(null, fromX, fromY, fromZ, SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1f, 1f);
				level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1f, 1f);
				return;
			}
		}
	}

	/** Entities that leave again after a while (the Warden, the chickens): tagged with the world time they go at. */
	static final String LEAVE_TAG = "bubble_leave_at_";

	static void markLeaving(Entity e, int seconds) {
		if (server != null && seconds > 0) {
			e.addTag(LEAVE_TAG + (server.overworld().getGameTime() + seconds * 20L));
		}
	}

	/** Called every second by Wheel: sends off the tagged entities whose time is up. */
	static void sendOffLeavers(MinecraftServer s) {
		long now = s.overworld().getGameTime();
		for (ServerLevel level : s.getAllLevels()) {
			List<Entity> gone = new ArrayList<>();
			for (Entity e : level.getAllEntities()) {
				for (String t : e.getTags()) {
					if (t.startsWith(LEAVE_TAG)) {
						try {
							if (Long.parseLong(t.substring(LEAVE_TAG.length())) <= now) {
								gone.add(e);
							}
						} catch (NumberFormatException ignored) {
							gone.add(e);
						}
						break;
					}
				}
			}
			for (Entity e : gone) {
				level.sendParticles(ParticleTypes.POOF, e.getX(), e.getY() + e.getBbHeight() / 2, e.getZ(), 12, 0.4, 0.4, 0.4, 0.02);
				e.discard();
			}
		}
	}
}
