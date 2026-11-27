package moonahmc.bubble;

import moonahmc.bubble.net.ServerPlayNetworking;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;

/** All gameplay runs here on the server; clients only draw the forcefield and the screens. */
public final class BubbleServer {
	/**
	 * Damage that always kills. Never Float.MAX_VALUE: armor maths (Apotheosis changes it) can turn infinite damage into
	 * NaN health, which leaves a player "dead" at 0 hp forever without actually dying.
	 */
	public static final float LETHAL = 100_000f;
	private static final int WARN_DISTANCE = 12;
	/** In push mode, anyone this far past the edge (e.g. on an elytra) is teleported instead. */
	private static final int PUSH_ESCAPE = 24;
	private static final int FOLLOW_DELAY_TICKS = 40;
	private static final int KILLCAM_TICKS = 160;
	private static final int POSTGAME_TICKS = 1200;
	/** Push mode: stuck outside (in a wall, or not getting any closer) for this long = teleported back. */
	private static final int STUCK_TICKS = 80;
	private static final int STUCK_IN_WALL_TICKS = 50;
	private static final DustParticleOptions EDGE = new DustParticleOptions(new Vector3f(0.49f, 0.77f, 0.64f), 1.3f);

	enum Phase { PLAYING, KILLCAM, POSTGAME, STOPPING }

	static BubbleConfig config;
	private static Path configPath;
	private static MinecraftServer server;
	private static long tick;
	private static Phase phase = Phase.PLAYING;
	private static int phaseTicks;

	private static final Map<UUID, Long> lastPull = new HashMap<>();
	private static final Map<UUID, Long> lastPush = new HashMap<>();
	private static final Set<UUID> fieldShown = new HashSet<>();
	private static final Set<UUID> following = new HashSet<>();
	private static UUID followLeader;
	private static int followTicks = -1;

	// soul link
	private static float sharedHealth = -1;
	private static final Map<UUID, Float> lastHealth = new HashMap<>();
	private static boolean soulLinkKilling;
	private static Component pendingCause;

	// kill cam recording: the last few seconds of everyone's movement, plus what they were wearing
	private static final int TRACK_FRAMES = 60;
	private static final Map<UUID, ArrayDeque<Payloads.Frame>> tracks = new HashMap<>();
	private static final Map<UUID, List<ItemStack>> gear = new HashMap<>();
	private static final Map<UUID, Integer> lastHurt = new HashMap<>();
	/**
	 * Revive: the few seconds before each player who is down went down (and what they wore), kept until they get up.
	 * If they bleed out and that ends the run, the kill cam shows this instead of them crawling about.
	 */
	private record DownClip(List<Payloads.Frame> frames, List<ItemStack> gear, Vec3 pos, Payloads.Scene scene, int[] shot) {
	}

	/** Clip `subject`'s last few seconds, keeping the long shot that just hit them (if any) for the bullet cam. */
	private static Payloads.Scene clipWithShot(ServerPlayer subject, List<Payloads.Frame> frames, int[] shot) {
		return ReplayScene.clip(subject, frames, shot == null ? Set.of() : Set.of(shot[0]));
	}

	private static final Map<UUID, DownClip> downClips = new HashMap<>();

	/** Revive: `p` just went down. Keeps the clip for the kill cam and plays it back to them (their own down cam). */
	static void rememberDown(ServerPlayer p) {
		ArrayDeque<Payloads.Frame> track = tracks.get(p.getUUID());
		List<Payloads.Frame> frames = track == null ? List.of() : List.copyOf(track);
		List<ItemStack> worn = new ArrayList<>(GEAR_SLOTS.length);
		for (EquipmentSlot slot : GEAR_SLOTS) {
			worn.add(p.getItemBySlot(slot).copy());
		}
		int[] shot = ReplayScene.shotAt(p);
		Payloads.Scene scene = clipWithShot(p, frames, shot);
		downClips.put(p.getUUID(), new DownClip(frames, worn, p.position(), scene, shot));
		if (frames.size() >= 2) {
			// everyone gets it; their own setting says whether it plays (yours, everyone's, or none)
			Payloads.DownCam cam = new Payloads.DownCam(new Payloads.Killcam(p.getUUID(), p.getName().getString(),
					p.serverLevel().dimension().location().toString(), frames, worn, scene,
					shot == null ? 0 : shot[0], shot == null ? 0 : shot[1]));
			for (ServerPlayer q : p.server.getPlayerList().getPlayers()) {
				if (q.serverLevel() == p.serverLevel() && ServerPlayNetworking.canSend(q, Payloads.DownCam.ID)) {
					ServerPlayNetworking.send(q, cam);
				}
			}
		}
	}

	/** Bosses: `killer` just took down a boss (entity id `boss`): everyone in that world gets the replay (boss cam). */
	static void sendBossCam(ServerPlayer killer, int boss, String bossName) {
		ArrayDeque<Payloads.Frame> track = tracks.get(killer.getUUID());
		List<Payloads.Frame> frames = track == null ? List.of() : List.copyOf(track);
		if (frames.size() < 2) {
			return;
		}
		List<ItemStack> worn = new ArrayList<>(GEAR_SLOTS.length);
		for (EquipmentSlot slot : GEAR_SLOTS) {
			worn.add(killer.getItemBySlot(slot).copy());
		}
		Payloads.Scene scene = ReplayScene.clip(killer, frames, Set.of(boss));
		Payloads.BossCam cam = new Payloads.BossCam(new Payloads.Killcam(killer.getUUID(), killer.getName().getString(),
				killer.serverLevel().dimension().location().toString(), frames, worn, scene), bossName);
		for (ServerPlayer q : killer.server.getPlayerList().getPlayers()) {
			if (q.serverLevel() == killer.serverLevel() && ServerPlayNetworking.canSend(q, Payloads.BossCam.ID)) {
				ServerPlayNetworking.send(q, cam);
			}
		}
	}

	/** Revive: `p` got back up (or left), so their knockdown clip isn't needed. */
	static void forgetDown(ServerPlayer p) {
		downClips.remove(p.getUUID());
	}

	// end of run
	private static Component endCause = Component.empty();
	private static ServerLevel deathWorld;
	private static Vec3 deathPos;
	private static ArmorStand camera;
	private static List<Payloads.Frame> killFrames = List.of();
	private static double camStartAngle;
	private static Payloads.PostGame postGame;
	private static final Map<UUID, Integer> votes = new HashMap<>();
	private static final Set<String> seedSavers = new HashSet<>();
	private static String currentSeed = "";

	// push mode: how close each player outside the edge has got, and since when
	private record Stuck(double best, long since) {
	}

	private static final Map<UUID, Stuck> stuck = new HashMap<>();
	private static final Map<UUID, Long> lastLinkSound = new HashMap<>();

	// "Another life": where to put everyone back, and what the dead were carrying
	private record Safe(ResourceKey<Level> dim, Vec3 pos, float yaw, float pitch, long tick) {
	}

	/** curios = their accessories (Curios slots), or null. */
	private record Capture(ListTag inventory, int level, float progress, int total, int food, float saturation, GameType mode,
						   long tick, net.minecraft.nbt.CompoundTag curios) {
	}

	private record Restore(ResourceKey<Level> dim, Vec3 pos, float yaw, float pitch, GameType mode, Capture capture) {
	}

	private static final Map<UUID, ArrayDeque<Safe>> safeSpots = new HashMap<>();
	private static final Map<UUID, Capture> deathCaptures = new HashMap<>();
	private static final Map<UUID, Restore> restorePoints = new HashMap<>();

	private BubbleServer() {
	}

	static void init() {
		configPath = FMLPaths.CONFIGDIR.get().resolve("bubble.json");
		config = BubbleConfig.load(configPath);

		var bus = NeoForge.EVENT_BUS;
		ReplayScene.init();
		bus.addListener((ServerStartedEvent e) -> {
			MinecraftServer s = e.getServer();
			server = s;
			phase = Phase.PLAYING;
			following.clear();
			votes.clear();
			sharedHealth = -1;
			lastHealth.clear();
			currentSeed = Long.toString(s.overworld().getSeed());
			Matter.onServerStarted(s);
			Shop.onServerStarted(s);
			// Runs end on death anyway, so skip the death screen and go straight to the kill cam.
			s.getGameRules().getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(config.hardcoreReset, s);
			Bubble.LOG.info("Bubble ready: run #{}, bubble {}, radius {}, edge {}, soul link {}, hardcore reset {}, shape {}, seed {}",
					config.run, config.bubbleEnabled ? "on" : "off", config.radius, edgeName(),
					config.soulLink ? "on" : "off", config.hardcoreReset ? "on" : "off", config.vertical ? "sphere" : "ring", currentSeed);
		});
		bus.addListener((ServerStoppedEvent e) -> {
			server = null;
			Matter.onServerStopped();
			Shop.onServerStopped();
		});
		bus.addListener((ServerTickEvent.Post e) -> tick(e.getServer()));
		bus.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
			if (e.getEntity() instanceof ServerPlayer player && player.getServer() != null) {
				onChangeWorld(player, player.getServer().getLevel(e.getFrom()), player.getServer().getLevel(e.getTo()));
			}
		});
		// Keep the inventory for "Another life" before it drops (or goes into a grave). The rest of the death
		// handling runs once the player has finished dying (see ServerPlayerMixin -> afterDeath).
		bus.addListener(EventPriority.HIGHEST, (LivingDeathEvent e) -> {
			if (e.getEntity() instanceof ServerPlayer player) {
				// Revive: knocked down instead of dying (the rest of the death handling doesn't happen)
				if (Revive.tryDown(player, e.getSource())) {
					e.setCanceled(true);
					return;
				}
				beforeDeath(player);
			}
		});
		bus.addListener((PlayerEvent.PlayerRespawnEvent e) -> {
			if (e.getEntity() instanceof ServerPlayer player) {
				onRespawn(player);
			}
		});
		bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
			if (e.getEntity() instanceof ServerPlayer player) {
				player.server.execute(() -> onJoin(player));
			}
		});
		bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
			if (e.getEntity() instanceof ServerPlayer player) {
				player.server.execute(() -> {
					votes.remove(player.getUUID());
					lastHealth.remove(player.getUUID());
					stuck.remove(player.getUUID());
					broadcastState();
				});
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Update.ID,
				(payload, context) -> context.server().execute(() -> onUpdate(context.player(), payload)));
		ServerPlayNetworking.registerGlobalReceiver(Eidolons.Action.ID,
				(payload, context) -> context.server().execute(() -> Eidolons.action(context.player(), payload.action(), payload.id())));
		ServerPlayNetworking.registerGlobalReceiver(Eidolons.PlaceBet.ID,
				(payload, context) -> context.server().execute(() -> Eidolons.placeBet(context.player(), payload.option(), payload.amount())));
		ServerPlayNetworking.registerGlobalReceiver(Gamble.Skip.ID,
				(payload, context) -> context.server().execute(() -> Gamble.skip(context.player())));
		ServerPlayNetworking.registerGlobalReceiver(Lobby.Ready.ID,
				(payload, context) -> context.server().execute(() -> Lobby.setReady(context.player(), payload.ready())));
		ServerPlayNetworking.registerGlobalReceiver(Lobby.Admin.ID,
				(payload, context) -> context.server().execute(() -> admin(context.player(), payload.action())));
		ServerPlayNetworking.registerGlobalReceiver(PregamePayloads.Update.ID,
				(payload, context) -> context.server().execute(() -> Pregame.update(context.player(), payload.settings())));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Vote.ID,
				(payload, context) -> context.server().execute(() -> vote(context.player(), payload.option())));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.SaveSeed.ID,
				(payload, context) -> context.server().execute(() -> toggleSaveSeed(context.player())));
		// Strange Matter + shop
		ServerPlayNetworking.registerGlobalReceiver(ShopPayloads.Request.ID,
				(payload, context) -> context.server().execute(() -> Shop.sendCatalog(context.player())));
		ServerPlayNetworking.registerGlobalReceiver(ShopPayloads.Buy.ID,
				(payload, context) -> context.server().execute(() -> Shop.buy(context.player(), payload)));
		ServerPlayNetworking.registerGlobalReceiver(ShopPayloads.Sell.ID,
				(payload, context) -> context.server().execute(() -> Matter.sell(context.player(), payload.all())));
		ServerPlayNetworking.registerGlobalReceiver(ShopPayloads.QuickSell.ID,
				(payload, context) -> context.server().execute(() -> Matter.quickSell(context.player(), payload.slot())));
		ServerPlayNetworking.registerGlobalReceiver(ShopPayloads.BuyBack.ID,
				(payload, context) -> context.server().execute(() -> Matter.buyBack(context.player())));
		ServerPlayNetworking.registerGlobalReceiver(ShopPayloads.Worth.ID,
				(payload, context) -> context.server().execute(() -> Matter.sendWorth(context.player(), payload.stacks())));
		ServerPlayNetworking.registerGlobalReceiver(ShopPayloads.Quote.ID,
				(payload, context) -> context.server().execute(() -> Matter.sendQuote(context.player(), payload.stack())));
		ServerPlayNetworking.registerGlobalReceiver(ShopPayloads.ChargeQuery.ID,
				(payload, context) -> context.server().execute(() -> Shop.sendCharges(context.player())));
		ServerPlayNetworking.registerGlobalReceiver(ShopPayloads.Charge.ID,
				(payload, context) -> context.server().execute(() -> Shop.charge(context.player(), payload.slot())));
		ServerPlayNetworking.registerGlobalReceiver(ShopPayloads.Edit.ID,
				(payload, context) -> context.server().execute(() -> Shop.edit(context.player(), payload)));
		bus.addListener((RegisterCommandsEvent e) -> BubbleCommand.register(e.getDispatcher(), e.getBuildContext()));
	}

	/** Players who are part of the bubble: alive survival/adventure players (spectators and creative are free). */
	/** A run is being played (not the kill cam, the post-game vote or a restart). */
	static boolean playing() {
		return phase == Phase.PLAYING;
	}

	static boolean counts(ServerPlayer p) {
		return p.isAlive() && !p.isSpectator() && !p.isCreative();
	}

	private static List<ServerPlayer> players() {
		return server == null ? List.of() : new ArrayList<>(server.getPlayerList().getPlayers());
	}

	private static void tick(MinecraftServer s) {
		tick++;
		if (tick % 10 == 0) {
			rescueBrokenHealth(s);
		}
		switch (phase) {
			case KILLCAM -> tickKillcam();
			case POSTGAME -> tickPostGame();
			case STOPPING -> {
			}
			case PLAYING -> {
				if (followTicks > 0 && --followTicks == 0) {
					doFollow(s);
				}
				tickSoulLink();
				tickBubble(s);
				record(s);
			}
		}
	}

	/**
	 * Safety net: a player whose health became NaN (some mod or command dealt "infinite" damage) is stuck at 0 hp without
	 * dying: no death screen, no kill cam, can't do anything. Let them die properly instead.
	 */
	private static void rescueBrokenHealth(MinecraftServer s) {
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (Float.isNaN(p.getHealth()) || Float.isInfinite(p.getHealth())) {
				Bubble.LOG.warn("{} had broken health ({}); letting them die properly", p.getGameProfile().getName(), p.getHealth());
				p.setHealth(1.0f);
				p.invulnerableTime = 0;
				p.hurt(p.damageSources().genericKill(), LETHAL);
			}
		}
	}

	// ------------------------------------------------------------------ kill cam recording

	private static final EquipmentSlot[] GEAR_SLOTS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
			EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	private static void record(MinecraftServer s) {
		List<ServerPlayer> watched = new ArrayList<>();
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (!counts(p)) {
				tracks.remove(p.getUUID());
				continue;
			}
			watched.add(p);
			byte flags = 0;
			if (p.isCrouching()) {
				flags |= Payloads.Frame.CROUCH;
			}
			if (p.getPose() == Pose.SWIMMING) {
				flags |= Payloads.Frame.SWIM;
			}
			if (p.getPose() == Pose.FALL_FLYING) {
				flags |= Payloads.Frame.GLIDE;
			}
			if (p.swinging && p.swingTime <= 1) {
				flags |= Payloads.Frame.SWING;
			}
			Integer prevHurt = lastHurt.put(p.getUUID(), p.hurtTime);
			if (p.hurtTime > 0 && (prevHurt == null || p.hurtTime > prevHurt)) {
				flags |= Payloads.Frame.HURT;
			}
			ArrayDeque<Payloads.Frame> track = tracks.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
			track.addLast(new Payloads.Frame(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), p.getYHeadRot(), flags));
			while (track.size() > TRACK_FRAMES) {
				track.removeFirst();
			}
			// Equipment is gone by the time the death event fires (it's already dropped), so keep a recent copy.
			if ((tick + p.getId()) % 10 == 0) {
				List<ItemStack> worn = new ArrayList<>(GEAR_SLOTS.length);
				for (EquipmentSlot slot : GEAR_SLOTS) {
					worn.add(p.getItemBySlot(slot).copy());
				}
				gear.put(p.getUUID(), worn);
			}
			// Somewhere safe to come back to for "Another life": standing on the ground, not burning, not hurting.
			if ((tick + p.getId()) % 20 == 0 && p.onGround() && !p.isInLava() && !p.isOnFire() && p.fallDistance == 0
					&& p.getHealth() >= Math.min(6, p.getMaxHealth()) && !p.isInWall()) {
				ArrayDeque<Safe> spots = safeSpots.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
				spots.addLast(new Safe(p.level().dimension(), p.position(), p.getYRot(), p.getXRot(), tick));
				while (spots.size() > 20) {
					spots.removeFirst();
				}
			}
		}
		ReplayScene.record(s, tick, watched);
	}

	// ------------------------------------------------------------------ bubble

	private static void tickBubble(MinecraftServer s) {
		Set<UUID> inField = new HashSet<>();
		if (config.bubbleEnabled) {
			Map<ServerLevel, List<ServerPlayer>> groups = new HashMap<>();
			for (ServerPlayer p : s.getPlayerList().getPlayers()) {
				if (counts(p)) {
					groups.computeIfAbsent(p.serverLevel(), w -> new ArrayList<>()).add(p);
				}
			}
			for (Map.Entry<ServerLevel, List<ServerPlayer>> e : groups.entrySet()) {
				if (e.getValue().size() >= 2) { // a bubble needs at least two people
					enforce(e.getKey(), e.getValue(), inField);
				}
			}
		}
		// Tell modded clients that just left the bubble (or had it turned off) to stop drawing it.
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (!inField.contains(p.getUUID()) && fieldShown.remove(p.getUUID()) && ServerPlayNetworking.canSend(p, Payloads.Field.ID)) {
				ServerPlayNetworking.send(p, Payloads.Field.OFF);
			}
		}
	}

	private static double median(double[] sorted) {
		int n = sorted.length;
		return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
	}

	/** Distance from the center: flat (ring) or straight-line (sphere). */
	private static double dist(Entity p, double cx, double cy, double cz) {
		double dx = p.getX() - cx;
		double dz = p.getZ() - cz;
		if (!config.vertical) {
			return Math.sqrt(dx * dx + dz * dz);
		}
		double dy = p.getY() - cy;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static void enforce(ServerLevel world, List<ServerPlayer> group, Set<UUID> inField) {
		// The center is the median of the group, so one runaway can't drag it away.
		double cx = median(group.stream().mapToDouble(ServerPlayer::getX).sorted().toArray());
		double cz = median(group.stream().mapToDouble(ServerPlayer::getZ).sorted().toArray());
		double cy = median(group.stream().mapToDouble(ServerPlayer::getY).sorted().toArray());
		int r = config.radius;
		for (ServerPlayer p : group) {
			double d = dist(p, cx, cy, cz);
			if (d > r) {
				double over = d - r;
				if (config.wrapMode) {
					stuck.remove(p.getUUID());
					wrap(world, p, group, cx, cy, cz);
				} else if (config.pushMode && over <= PUSH_ESCAPE && !isStuck(p, over)) {
					push(p, cx, cy, cz, over);
				} else {
					stuck.remove(p.getUUID());
					pullBack(world, p, group, cx, cy, cz);
				}
			} else {
				stuck.remove(p.getUUID());
			}
			boolean modded = ServerPlayNetworking.canSend(p, Payloads.Field.ID);
			if (modded) {
				inField.add(p.getUUID());
				fieldShown.add(p.getUUID());
				if (tick % 4 == 0) {
					Long pushed = lastPush.get(p.getUUID());
					ServerPlayNetworking.send(p, new Payloads.Field(true, cx, cy, cz, r, config.vertical, pushed != null && tick - pushed < 8));
				}
			} else if (d > r - WARN_DISTANCE && d <= r) {
				showEdgeParticles(world, p, cx, cy, cz, r);
			}
			if (d > r - WARN_DISTANCE && d <= r && tick % 10 == 0) {
				int left = (int) Math.ceil(r - d);
				p.displayClientMessage(Component.literal("Bubble edge in " + left + (left == 1 ? " block" : " blocks")).withStyle(ChatFormatting.AQUA), true);
			}
		}
	}

	/** Push mode: true once someone has been outside without getting any closer (walled in, stuck, or fighting it). */
	private static boolean isStuck(ServerPlayer p, double over) {
		Stuck st = stuck.get(p.getUUID());
		if (st == null || over < st.best() - 0.4) {
			stuck.put(p.getUUID(), new Stuck(over, tick));
			return false;
		}
		int limit = p.isInWall() ? STUCK_IN_WALL_TICKS : STUCK_TICKS;
		if (tick - st.since() >= limit) {
			p.displayClientMessage(Component.literal("Stuck outside the bubble, teleporting you back").withStyle(ChatFormatting.AQUA), true);
			return true;
		}
		return false;
	}

	private static void push(ServerPlayer p, double cx, double cy, double cz, double over) {
		Vec3 dir = new Vec3(cx - p.getX(), config.vertical ? cy - p.getY() : 0, cz - p.getZ()).normalize();
		double strength = 0.45 + Math.min(1.4, over * 0.12);
		Entity mover = p.isPassenger() ? p.getRootVehicle() : p;
		Vec3 v = mover.getDeltaMovement();
		double vy = config.vertical ? dir.y * strength + 0.15 * (1 - Math.abs(dir.y)) : Math.max(v.y, 0.15);
		mover.setDeltaMovement(dir.x * strength, vy, dir.z * strength);
		mover.hurtMarked = true;
		if (mover == p) {
			p.connection.send(new ClientboundSetEntityMotionPacket(p));
		}
		Long last = lastPush.put(p.getUUID(), tick);
		if (last == null || tick - last > 20) {
			p.serverLevel().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.5f, 1.6f);
		}
	}

	/**
	 * Bubble wrap: out one side, in the other. They come back in on the opposite side of the bubble (a couple of blocks
	 * inside), at the open spot nearest their own height, still facing and moving the same way. On foot they land on
	 * the ground; flying (elytra, creative-style flight) they can come out in the air. No room there (the ocean, inside
	 * a mountain): pulled back to the nearest player as usual.
	 */
	private static void wrap(ServerLevel world, ServerPlayer p, List<ServerPlayer> group, double cx, double cy, double cz) {
		Long last = lastPull.get(p.getUUID());
		if (last != null && tick - last < 10) {
			return;
		}
		Vec3 spot = wrapSpot(world, p, cx, cy, cz);
		if (spot == null) {
			pullBack(world, p, group, cx, cy, cz);
			return;
		}
		lastPull.put(p.getUUID(), tick);
		if (p.isPassenger()) {
			p.stopRiding();
		}
		Vec3 from = p.position();
		Vec3 v = p.getDeltaMovement();
		boolean air = airborne(p);
		world.sendParticles(net.minecraft.core.particles.ParticleTypes.REVERSE_PORTAL, from.x, from.y + 1, from.z, 30, 0.3, 0.6, 0.3, 0.05);
		world.playSound(null, from.x, from.y, from.z, SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 0.6f, 1.4f);
		p.teleportTo(world, spot.x, spot.y, spot.z, p.getYRot(), p.getXRot());
		if (!air) {
			p.fallDistance = 0;
		}
		p.setDeltaMovement(v);
		p.hurtMarked = true;
		p.connection.send(new ClientboundSetEntityMotionPacket(p));
		world.sendParticles(net.minecraft.core.particles.ParticleTypes.REVERSE_PORTAL, spot.x, spot.y + 1, spot.z, 30, 0.3, 0.6, 0.3, 0.05);
		world.playSound(null, spot.x, spot.y, spot.z, SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 0.6f, 1.6f);
		p.displayClientMessage(Component.literal("Bubble wrap! Back in on the other side").withStyle(ChatFormatting.AQUA), true);
	}

	/**
	 * Ops: /bubble wraptest. A stand-in player just past the edge in 8 directions round the source (as the bubble's
	 * center): where bubble wrap would put them. Nothing is moved.
	 */
	static int wrapTest(net.minecraft.commands.CommandSourceStack src) {
		ServerLevel level = src.getLevel();
		ServerPlayer fake = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
		Vec3 c = src.getPosition();
		int r = config.radius;
		StringBuilder sb = new StringBuilder("wraptest r=" + r + ":");
		for (int k = 0; k < 8; k++) {
			double a = k * Math.PI / 4;
			double x = c.x + Math.cos(a) * (r + 1);
			double z = c.z + Math.sin(a) * (r + 1);
			int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
			fake.moveTo(x, y, z);
			Vec3 to = wrapSpot(level, fake, c.x, c.y, c.z);
			sb.append(String.format(java.util.Locale.ROOT, " [%d: from %.0f,%d,%.0f -> %s]", k * 45, x, y, z,
					to == null ? "none (pull back)" : String.format(java.util.Locale.ROOT, "%.0f,%.0f,%.0f d=%.1f", to.x, to.y, to.z,
							Math.hypot(to.x - c.x, to.z - c.z))));
		}
		String msg = sb.toString();
		src.sendSuccess(() -> Component.literal(msg), false);
		Bubble.LOG.info(msg);
		return 1;
	}

	/** Gliding or flying: they can come back in mid-air. */
	private static boolean airborne(ServerPlayer p) {
		return p.isFallFlying() || p.getAbilities().flying;
	}

	/** Where `p` comes back in: straight across the bubble from where they left, a little inside the edge. */
	private static Vec3 wrapSpot(ServerLevel world, ServerPlayer p, double cx, double cy, double cz) {
		Vec3 off = new Vec3(p.getX() - cx, config.vertical ? p.getY() - cy : 0, p.getZ() - cz);
		double len = off.length();
		if (len < 1e-3) {
			return null;
		}
		Vec3 u = off.scale(1 / len);
		int r = config.radius;
		boolean air = airborne(p);
		// a couple of blocks in from the far edge; no room there, try a bit further in
		for (int in = 2; in <= Math.max(2, Math.min(16, r / 2)); in++) {
			double tx = cx - u.x * (r - in);
			double tz = cz - u.z * (r - in);
			double ty = config.vertical ? cy - u.y * (r - in) : p.getY();
			net.minecraft.core.BlockPos at = openNear(world, net.minecraft.util.Mth.floor(tx), net.minecraft.util.Mth.floor(ty), net.minecraft.util.Mth.floor(tz), 24, air);
			if (at != null) {
				return new Vec3(tx, at.getY(), tz);
			}
		}
		if (!config.vertical) {
			// nothing open near their height (a mountain or a cliff there): the top of the ground, unless it's water
			double tx = cx - u.x * (r - 2);
			double tz = cz - u.z * (r - 2);
			int x = net.minecraft.util.Mth.floor(tx);
			int z = net.minecraft.util.Mth.floor(tz);
			world.getChunk(x >> 4, z >> 4);
			int top = world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, top, z);
			if (top > world.getMinBuildHeight() + 1 && roomAt(world, pos) && roomAt(world, pos.above())) {
				return new Vec3(tx, top, tz);
			}
		}
		return null;
	}

	/** The nearest spot up or down from y with room for a player (and ground under it unless they can be in the air). */
	private static net.minecraft.core.BlockPos openNear(ServerLevel world, int x, int y, int z, int range, boolean air) {
		world.getChunk(x >> 4, z >> 4); // load it (it may be far off)
		for (int i = 0; i <= range * 2; i++) {
			int dy = (i % 2 == 0) ? i / 2 : -(i + 1) / 2;
			int yy = y + dy;
			if (yy <= world.getMinBuildHeight() + 1 || yy >= world.getMaxBuildHeight() - 2) {
				continue;
			}
			net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, yy, z);
			if (!roomAt(world, pos) || !roomAt(world, pos.above())) {
				continue;
			}
			net.minecraft.core.BlockPos below = pos.below();
			boolean ground = world.getBlockState(below).isFaceSturdy(world, below, net.minecraft.core.Direction.UP);
			if (ground || air) {
				return pos;
			}
		}
		return null;
	}

	private static boolean roomAt(ServerLevel world, net.minecraft.core.BlockPos pos) {
		return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty() && world.getFluidState(pos).isEmpty();
	}

	private static void pullBack(ServerLevel world, ServerPlayer p, List<ServerPlayer> group, double cx, double cy, double cz) {
		Long last = lastPull.get(p.getUUID());
		if (last != null && tick - last < 10) {
			return;
		}
		// Nearest other player who is safely inside the bubble (or simply the nearest, if nobody is).
		ServerPlayer target = null;
		double best = Double.MAX_VALUE;
		for (ServerPlayer q : group) {
			if (q == p) {
				continue;
			}
			boolean inside = dist(q, cx, cy, cz) <= config.radius - 1;
			double score = p.distanceToSqr(q) + (inside ? 0 : 1e12);
			if (score < best) {
				best = score;
				target = q;
			}
		}
		if (target == null) {
			return;
		}
		lastPull.put(p.getUUID(), tick);
		if (p.isPassenger()) {
			p.stopRiding();
		}
		p.teleportTo(world, target.getX(), target.getY(), target.getZ(), p.getYRot(), p.getXRot());
		p.fallDistance = 0;
		p.displayClientMessage(Component.literal("Pulled back to ").withStyle(ChatFormatting.GRAY).append(target.getDisplayName()), true);
		world.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.6f, 1.3f);
	}

	/** For players without the mod: a short arc of particles on the edge in front of them. */
	private static void showEdgeParticles(ServerLevel world, ServerPlayer p, double cx, double cy, double cz, int r) {
		if (tick % 3 != 0) {
			return;
		}
		if (config.vertical) {
			// A small patch of the sphere's surface in the player's direction.
			Vec3 n = new Vec3(p.getX() - cx, p.getY() - cy, p.getZ() - cz);
			if (n.lengthSqr() < 1e-6) {
				return;
			}
			n = n.normalize();
			Vec3 c = new Vec3(cx, cy, cz).add(n.scale(r));
			Vec3 up = Math.abs(n.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
			Vec3 t1 = n.cross(up).normalize();
			Vec3 t2 = n.cross(t1).normalize();
			for (double a = -6; a <= 6; a += 1.5) {
				for (double b = -4; b <= 4; b += 2) {
					Vec3 q = c.add(t1.scale(a)).add(t2.scale(b));
					world.sendParticles(p, EDGE, true, q.x, q.y + 1, q.z, 1, 0, 0, 0, 0);
				}
			}
			return;
		}
		double angle = Math.atan2(p.getZ() - cz, p.getX() - cx);
		double span = Math.min(Math.PI, 10.0 / r);
		double step = Math.max(0.6 / r, 0.02);
		for (double a = angle - span; a <= angle + span; a += step) {
			double x = cx + Math.cos(a) * r;
			double z = cz + Math.sin(a) * r;
			for (int dy = -1; dy <= 3; dy += 2) {
				world.sendParticles(p, EDGE, true, x, p.getY() + dy, z, 1, 0, 0, 0, 0);
			}
		}
	}

	// ------------------------------------------------------------------ soul link

	private static void tickSoulLink() {
		List<ServerPlayer> linked = players().stream().filter(BubbleServer::counts).toList();
		if (!config.soulLink || linked.size() < 2) {
			sharedHealth = -1;
			lastHealth.clear();
			return;
		}
		float maxShared = (float) linked.stream().mapToDouble(ServerPlayer::getMaxHealth).min().orElse(20);
		if (sharedHealth < 0) {
			sharedHealth = (float) linked.stream().mapToDouble(ServerPlayer::getHealth).min().orElse(maxShared);
		}
		// Damage from everyone adds up; healing doesn't (the best heal this tick counts once).
		float damage = 0;
		float heal = 0;
		List<ServerPlayer> hurt = new ArrayList<>();
		for (ServerPlayer p : linked) {
			Float last = lastHealth.get(p.getUUID());
			if (last == null) {
				continue;
			}
			float delta = p.getHealth() - last;
			if (delta < 0) {
				damage -= delta;
				if (delta < -0.05f) {
					hurt.add(p);
				}
			} else {
				heal = Math.max(heal, delta);
			}
		}
		// Everyone else feels it: a quieter, higher-pitched hurt sound and a small camera shake.
		for (ServerPlayer q : linked) {
			if (hurt.isEmpty() || (hurt.size() == 1 && hurt.get(0) == q)) {
				continue;
			}
			Long last = lastLinkSound.get(q.getUUID());
			if (last == null || tick - last >= 6) {
				lastLinkSound.put(q.getUUID(), tick);
				q.playNotifySound(SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 0.35f, 1.75f);
				q.connection.send(new ClientboundHurtAnimationPacket(q.getId(), 0));
			}
		}
		sharedHealth = Math.max(0, Math.min(maxShared, sharedHealth - damage + heal));
		if (sharedHealth <= 0) {
			// Whoever took the biggest hit this tick is the one who "died"; the link takes everyone else with them.
			ServerPlayer victim = linked.get(0);
			float worst = 0;
			for (ServerPlayer p : linked) {
				Float last = lastHealth.get(p.getUUID());
				float lost = last == null ? 0 : last - p.getHealth();
				if (lost > worst) {
					worst = lost;
					victim = p;
				}
			}
			pendingCause = victim.getCombatTracker().getDeathMessage();
			sharedHealth = -1;
			lastHealth.clear();
			victim.hurt(victim.damageSources().genericKill(), LETHAL);
			pendingCause = null;
			return;
		}
		for (ServerPlayer p : linked) {
			float target = Math.min(sharedHealth, p.getMaxHealth());
			if (Math.abs(p.getHealth() - target) > 0.01f) {
				p.setHealth(target);
			}
			lastHealth.put(p.getUUID(), target);
		}
	}

	// ------------------------------------------------------------------ portals: everyone follows

	private static void onChangeWorld(ServerPlayer player, ServerLevel origin, ServerLevel destination) {
		tracks.remove(player.getUUID()); // the replay only shows movement in the world they died in
		if (following.remove(player.getUUID())) {
			return;
		}
		if (!config.bubbleEnabled || phase != Phase.PLAYING || server == null || !counts(player)) {
			return;
		}
		List<ServerPlayer> others = players().stream()
				.filter(q -> q != player && counts(q) && q.serverLevel() != destination).toList();
		if (others.isEmpty()) {
			return;
		}
		followLeader = player.getUUID();
		followTicks = FOLLOW_DELAY_TICKS;
		Component title = Component.literal("Following ").withStyle(ChatFormatting.AQUA).append(player.getDisplayName());
		Component sub = Component.literal("to " + dimensionName(destination)).withStyle(ChatFormatting.GRAY);
		for (ServerPlayer q : others) {
			title(q, title, sub, 5, 30, 10);
		}
	}

	private static void doFollow(MinecraftServer s) {
		ServerPlayer leader = followLeader == null ? null : s.getPlayerList().getPlayer(followLeader);
		followLeader = null;
		if (leader == null || !leader.isAlive()) {
			return;
		}
		ServerLevel w = leader.serverLevel();
		for (ServerPlayer q : players()) {
			if (q == leader || !counts(q) || q.serverLevel() == w) {
				continue;
			}
			following.add(q.getUUID());
			if (q.isPassenger()) {
				q.stopRiding();
			}
			q.teleportTo(w, leader.getX(), leader.getY(), leader.getZ(), q.getYRot(), q.getXRot());
			q.fallDistance = 0;
			following.remove(q.getUUID());
		}
	}

	private static String dimensionName(ServerLevel world) {
		String path = world.dimension().location().getPath();
		return switch (path) {
			case "overworld" -> "the Overworld";
			case "the_nether" -> "the Nether";
			case "the_end" -> "the End";
			default -> path.replace('_', ' ');
		};
	}

	// ------------------------------------------------------------------ end of run: kill cam -> post-game -> new world

	/**
	 * Called (from LivingDeathEvent) just before a player dies and drops everything. If this death will end the run, keep their
	 * inventory and XP for "Another life" instead of dropping it; if the vote goes the other way the world is thrown away anyway.
	 */
	public static void beforeDeath(ServerPlayer p) {
		if (server == null || phase != Phase.PLAYING || !config.hardcoreReset || !server.isDedicatedServer() || p.isSpectator() || p.isCreative()) {
			return;
		}
		var inv = p.getInventory();
		deathCaptures.put(p.getUUID(), new Capture(inv.save(new ListTag()), p.experienceLevel, p.experienceProgress,
				p.totalExperience, p.getFoodData().getFoodLevel(), p.getFoodData().getSaturationLevel(),
				p.gameMode.getGameModeForPlayer(), tick, AccessoryCompat.capture(p)));
		inv.clearContent();
		p.experienceLevel = 0;
		p.experienceProgress = 0;
		p.totalExperience = 0;
	}

	/** Called from ServerPlayerMixin once a player has finished dying (drops, death message and stats are done). */
	public static void afterDeath(ServerPlayer player, DamageSource source) {
		RunTally.death(player); // ☠ in the tab list
		onDeath(player, source);
	}

	private static void onDeath(ServerPlayer player, DamageSource source) {
		if (phase != Phase.PLAYING || server == null || soulLinkKilling) {
			return; // soulLinkKilling: this death was caused by someone else's, which handles the rest
		}
		Component cause = pendingCause != null ? pendingCause : source.getLocalizedDeathMessage(player);
		// Soul link: one of you dies, all of you die.
		List<ServerPlayer> others = config.soulLink
				? players().stream().filter(q -> q != player && counts(q)).toList() : List.of();
		if (!others.isEmpty()) {
			soulLinkKilling = true;
			try {
				for (ServerPlayer q : others) {
					q.hurt(q.damageSources().genericKill(), LETHAL);
				}
			} finally {
				soulLinkKilling = false;
			}
			sharedHealth = -1;
			lastHealth.clear();
			server.getPlayerList().broadcastSystemMessage(Component.literal("❤ The soul link took everyone down with ").withStyle(ChatFormatting.LIGHT_PURPLE)
					.append(player.getDisplayName()), false);
		}
		if (config.hardcoreReset) {
			endRun(cause, player);
		}
	}

	/** Ends the run. With a victim there's a kill cam first; otherwise it goes straight to the post-game. */
	static boolean endRun(Component cause, ServerPlayer victim) {
		if (server == null || phase != Phase.PLAYING) {
			return false;
		}
		if (!server.isDedicatedServer()) {
			server.getPlayerList().broadcastSystemMessage(Component.literal("Hardcore reset only runs on a dedicated server.").withStyle(ChatFormatting.GRAY), false);
			return false;
		}
		endCause = cause;
		lastEnder = victim == null ? null : victim.getUUID();
		followTicks = -1;
		saveRestorePoints();
		server.getPlayerList().broadcastSystemMessage(Component.literal("☠ ").withStyle(ChatFormatting.RED).append(cause.copy().withStyle(ChatFormatting.RED))
				.append(Component.literal(". Run #" + config.run + " is over.").withStyle(ChatFormatting.GRAY)), false);
		for (ServerPlayer q : players()) {
			if (q.isPassenger()) {
				q.stopRiding();
			}
			q.setGameMode(GameType.SPECTATOR);
			hideField(q);
		}
		stuck.clear();
		if (victim != null) {
			startKillcam(victim);
		} else {
			startPostGame();
		}
		return true;
	}

	private static void startKillcam(ServerPlayer victim) {
		phase = Phase.KILLCAM;
		phaseTicks = 0;
		deathWorld = victim.serverLevel();
		deathPos = victim.position();
		ArrayDeque<Payloads.Frame> track = tracks.get(victim.getUUID());
		killFrames = track == null ? List.of() : List.copyOf(track);
		List<ItemStack> victimGear = gear.getOrDefault(victim.getUUID(), List.of());
		int[] shot = ReplayScene.shotAt(victim);
		Payloads.Scene scene = clipWithShot(victim, killFrames, shot);
		// They went down earlier and bled out: show how they went down, not them crawling about.
		DownClip clip = downClips.get(victim.getUUID());
		if (clip != null && clip.frames.size() >= 2) {
			killFrames = clip.frames;
			victimGear = clip.gear;
			scene = clip.scene;
			shot = clip.shot;
			deathPos = clip.pos;
			endCause = endCause.copy().append(Component.literal(" (this is where they went down)"));
		}
		downClips.clear();
		// Start behind the player, looking over their shoulder.
		float yaw = killFrames.isEmpty() ? victim.getYRot() : killFrames.get(0).yaw();
		camStartAngle = Math.toRadians(yaw) - Math.PI / 2;
		Vec3 start = camTarget(0);
		camera = new ArmorStand(deathWorld, start.x, start.y + 2, start.z);
		camera.setInvisible(true);
		camera.setNoGravity(true);
		camera.setInvulnerable(true);
		camera.setSilent(true);
		deathWorld.addFreshEntity(camera);
		for (ServerPlayer q : players()) {
			bringToKillcam(q);
		}
		Payloads.Killcam replay = new Payloads.Killcam(victim.getUUID(), victim.getName().getString(),
				deathWorld.dimension().location().toString(), killFrames, victimGear, scene,
				shot == null ? 0 : shot[0], shot == null ? 0 : shot[1]);
		for (ServerPlayer q : players()) {
			if (ServerPlayNetworking.canSend(q, Payloads.Killcam.ID)) {
				ServerPlayNetworking.send(q, replay);
			}
		}
		tracks.clear();
		ReplayScene.clear();
	}

	/** Where the replayed player is `t` ticks into the kill cam (the death spot once the replay is over). */
	private static Vec3 camTarget(int t) {
		if (killFrames.isEmpty()) {
			return deathPos;
		}
		Payloads.Frame f = Payloads.Killcam.lerp(killFrames, Payloads.Killcam.frameAt(t, killFrames.size()));
		return new Vec3(f.x(), f.y(), f.z());
	}

	private static void bringToKillcam(ServerPlayer q) {
		q.setGameMode(GameType.SPECTATOR);
		q.teleportTo(deathWorld, deathPos.x, deathPos.y + 4, deathPos.z, q.getYRot(), q.getXRot());
		title(q, Component.literal("☠ Run #" + config.run + " over").withStyle(ChatFormatting.RED), endCause.copy().withStyle(ChatFormatting.GRAY), 5, KILLCAM_TICKS - 20, 10);
	}

	private static void tickKillcam() {
		phaseTicks++;
		if (camera != null && camera.isAlive()) {
			// Follow the replay from over the shoulder, then slowly circle the spot where they fell.
			Vec3 target = camTarget(phaseTicks);
			int replayEnd = Payloads.Killcam.replayTicks(killFrames.size());
			double a = camStartAngle + Math.toRadians(phaseTicks * (phaseTicks < replayEnd ? 0.5 : 1.4));
			double radius = phaseTicks < replayEnd ? 4.5 : 4.5 + Math.min(2.5, (phaseTicks - replayEnd) * 0.04);
			double x = target.x + Math.cos(a) * radius;
			double z = target.z + Math.sin(a) * radius;
			double y = target.y + 0.6; // armor stand eyes sit ~1.7 above its feet
			double dx = target.x - x;
			double dz = target.z - z;
			double dy = (target.y + 0.9) - (y + 1.7);
			float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
			float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
			camera.moveTo(x, y, z, yaw, pitch);
			camera.setYHeadRot(yaw);
			for (ServerPlayer q : players()) {
				if (q.isSpectator() && q.serverLevel() == deathWorld && q.getCamera() != camera) {
					q.setCamera(camera);
				}
			}
		}
		if (phaseTicks >= KILLCAM_TICKS) {
			endKillcamCamera();
			startPostGame();
		}
	}

	private static void endKillcamCamera() {
		for (ServerPlayer q : players()) {
			q.setCamera(q);
		}
		if (camera != null) {
			camera.discard();
			camera = null;
		}
	}

	private static void startPostGame() {
		phase = Phase.POSTGAME;
		phaseTicks = POSTGAME_TICKS;
		votes.clear();
		seedSavers.clear();
		SeedBook.Saved saved = SeedBook.find(currentSeed);
		if (saved != null) {
			seedSavers.addAll(saved.savedBy);
		}
		List<Payloads.PlayerStats> stats = new ArrayList<>();
		for (ServerPlayer q : players()) {
			stats.add(statsFor(q));
		}
		int duration = (int) Math.min(Integer.MAX_VALUE, server.overworld().getGameTime());
		postGame = new Payloads.PostGame(config.run, duration, endCause.getString(), stats, RunLog.lines());
		for (ServerPlayer q : players()) {
			sendPostGame(q);
		}
		broadcastSummary(stats, duration);
	}

	private static void sendPostGame(ServerPlayer q) {
		if (postGame != null && ServerPlayNetworking.canSend(q, Payloads.PostGame.ID)) {
			ServerPlayNetworking.send(q, postGame);
			sendPostStatus(q);
		}
	}

	private static void tickPostGame() {
		phaseTicks--;
		int online = server.getPlayerList().getPlayerCount();
		if (phaseTicks % 20 == 0) {
			broadcastPostStatus();
			int secs = phaseTicks / 20;
			if (secs == 30 || secs == 10 || (secs <= 5 && secs > 0)) {
				server.getPlayerList().broadcastSystemMessage(Component.literal("Next world in " + secs + "s").withStyle(ChatFormatting.GRAY), false);
			}
		}
		if (phaseTicks <= 0 || (online > 0 && votes.size() >= online)) {
			decide(winningVote());
		}
	}

	private static int[] voteCounts() {
		int[] counts = new int[3];
		for (int v : votes.values()) {
			counts[v]++;
		}
		return counts;
	}

	/** Most votes wins. No votes, or a tie that includes "Next world", means the next world. */
	private static int winningVote() {
		int[] c = voteCounts();
		int best = Payloads.VOTE_NEXT;
		for (int i = 1; i < 3; i++) {
			if (c[i] > c[best]) {
				best = i;
			}
		}
		return best;
	}

	private static void broadcastPostStatus() {
		for (ServerPlayer q : players()) {
			sendPostStatus(q);
		}
	}

	private static void sendPostStatus(ServerPlayer q) {
		if (ServerPlayNetworking.canSend(q, Payloads.PostStatus.ID)) {
			ServerPlayNetworking.send(q, new Payloads.PostStatus(Math.max(0, phaseTicks / 20), server.getPlayerList().getPlayerCount(),
					voteCounts(), votes.getOrDefault(q.getUUID(), -1), seedSavers.size(), seedSavers.contains(q.getName().getString()),
					currentSeed, config.livesUsed));
		}
	}

	private static final String[] VOTE_NAMES = {"the next world", "retrying this seed", "another life"};

	static boolean vote(ServerPlayer p, int option) {
		if (phase != Phase.POSTGAME || option < 0 || option > 2) {
			return false;
		}
		Integer old = votes.get(p.getUUID());
		if (old != null && old == option) {
			votes.remove(p.getUUID());
		} else {
			votes.put(p.getUUID(), option);
			int[] c = voteCounts();
			server.getPlayerList().broadcastSystemMessage(p.getDisplayName().copy().append(Component.literal(" voted for " + VOTE_NAMES[option]
					+ " (" + c[option] + "/" + server.getPlayerList().getPlayerCount() + ")").withStyle(ChatFormatting.GRAY)), false);
		}
		broadcastPostStatus();
		return true;
	}

	static boolean toggleSaveSeed(ServerPlayer p) {
		if (phase != Phase.POSTGAME) {
			return false;
		}
		String name = p.getName().getString();
		boolean saved = SeedBook.toggle(currentSeed, config.run, name);
		if (saved) {
			seedSavers.add(name);
			server.getPlayerList().broadcastSystemMessage(p.getDisplayName().copy().append(Component.literal(" saved this seed (" + currentSeed
					+ ") to the favourites ★").withStyle(ChatFormatting.YELLOW)), false);
		} else {
			seedSavers.remove(name);
		}
		broadcastPostStatus();
		return true;
	}

	/** Skips whatever is left of the kill cam / post-game. */
	static boolean skipToNextRun() {
		if (phase == Phase.KILLCAM) {
			phaseTicks = KILLCAM_TICKS;
			return true;
		}
		if (phase == Phase.POSTGAME) {
			phaseTicks = 0;
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ admin tab

	public static final int ADMIN_NEXT_WORLD = 0;
	public static final int ADMIN_RETRY_SEED = 1;
	public static final int ADMIN_END_RUN = 2;
	public static final int ADMIN_SKIP = 3;
	public static final int ADMIN_REVIVE = 4;
	public static final int ADMIN_WHEEL = 5;
	public static final int ADMIN_START = 6;
	public static final int ADMIN_SHOP = 7;
	/** + vote option: an op's shift-click on a post-game vote button decides it. */
	public static final int ADMIN_DECIDE = 10;

	static void admin(ServerPlayer by, int action) {
		if (!by.hasPermissions(2) || server == null) {
			return;
		}
		String who = by.getGameProfile().getName();
		boolean ok = switch (action) {
			case ADMIN_NEXT_WORLD, ADMIN_RETRY_SEED -> {
				if (phase == Phase.STOPPING) {
					yield false;
				}
				if (phase == Phase.KILLCAM) {
					endKillcamCamera();
				}
				server.getPlayerList().broadcastSystemMessage(Component.literal(who + (action == ADMIN_RETRY_SEED ? " restarted on the same seed." : " started a new world."))
						.withStyle(ChatFormatting.GOLD), false);
				startNextRun(action == ADMIN_RETRY_SEED);
				yield true;
			}
			case ADMIN_END_RUN -> endRun(Component.literal(who + " ended the run"), null);
			case ADMIN_SKIP -> skipToNextRun();
			case ADMIN_REVIVE -> revive(who);
			case ADMIN_WHEEL -> Wheel.spinNow();
			case ADMIN_START -> Lobby.start(by);
			case ADMIN_SHOP -> {
				Shop.setClosed(!Shop.closed, who);
				yield true;
			}
			default -> action >= ADMIN_DECIDE && action <= ADMIN_DECIDE + 2 && decide(action - ADMIN_DECIDE);
		};
		if (!ok) {
			Notice.send(by, false, "That can't be done right now", switch (action) {
				case ADMIN_START -> "The ready-up lobby isn't open.";
				case ADMIN_WHEEL -> "The wheel is already spinning.";
				case ADMIN_SKIP -> "There's no kill cam or vote to skip.";
				case ADMIN_REVIVE -> "Nobody is out right now.";
				default -> "";
			});
		} else {
			Bubble.LOG.info("Admin: {} used action {}", who, action);
			String[] what = switch (action) {
				case ADMIN_NEXT_WORLD -> new String[]{"Making a new world", "Everyone is moved over when it's ready."};
				case ADMIN_RETRY_SEED -> new String[]{"Restarting on the same seed", "A fresh copy of this world."};
				case ADMIN_END_RUN -> new String[]{"Run ended", "Going to the post-game vote."};
				case ADMIN_SKIP -> new String[]{"Skipped", ""};
				case ADMIN_REVIVE -> new String[]{"Revived", "Everyone who was out is back."};
				case ADMIN_WHEEL -> new String[]{"Wheel spinning", "The start-of-run wheel."};
				case ADMIN_START -> new String[]{"Run started", "The ready-up lobby is over."};
				case ADMIN_SHOP -> new String[]{Shop.closed ? "Shop closed" : "Shop open", Shop.closed ? "Gamble and selling still work." : "Buying is back on."};
				default -> new String[]{"Decided", "That's what happens next."};
			};
			Notice.send(by, true, what[0], what[1]);
		}
	}

	/** During a run: brings back everyone who's out (spectating). At the end of a run: Another life for everyone. */
	private static boolean revive(String who) {
		if (phase == Phase.KILLCAM || phase == Phase.POSTGAME) {
			return decide(Payloads.VOTE_LIFE);
		}
		if (phase != Phase.PLAYING) {
			return false;
		}
		ServerPlayer anchor = players().stream().filter(BubbleServer::counts).findFirst().orElse(null);
		int n = 0;
		for (ServerPlayer q : players()) {
			if (!q.isSpectator()) {
				continue;
			}
			q.setGameMode(GameType.SURVIVAL);
			if (anchor != null) {
				q.teleportTo(anchor.serverLevel(), anchor.getX(), anchor.getY(), anchor.getZ(), q.getYRot(), q.getXRot());
			}
			q.setHealth(q.getMaxHealth());
			q.getFoodData().setFoodLevel(20);
			q.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 100, 4));
			title(q, Component.literal("Revived").withStyle(ChatFormatting.LIGHT_PURPLE), Component.literal("by " + who).withStyle(ChatFormatting.GRAY), 5, 40, 10);
			n++;
		}
		if (n > 0) {
			server.getPlayerList().broadcastSystemMessage(Component.literal("❤ " + who + " revived " + n + (n == 1 ? " player." : " players."))
					.withStyle(ChatFormatting.LIGHT_PURPLE), false);
		}
		return n > 0;
	}

	/** Ends the kill cam / post-game right now with this outcome (votes, or an operator's /bubble decide). */
	static boolean decide(int option) {
		if (phase != Phase.KILLCAM && phase != Phase.POSTGAME) {
			return false;
		}
		if (phase == Phase.KILLCAM) {
			endKillcamCamera();
		}
		switch (option) {
			case Payloads.VOTE_RETRY -> startNextRun(true);
			case Payloads.VOTE_LIFE -> anotherLife();
			default -> startNextRun(false);
		}
		return true;
	}

	/** Whose death ended the run last (for Eidolon bets and the "Run Ender" title). */
	private static java.util.UUID lastEnder;

	private static void startNextRun(boolean retry) {
		phase = Phase.STOPPING;
		int finished = config.run;
		try {
			Eidolons.runFinished(lastEnder == null ? null : server.getPlayerList().getPlayer(lastEnder),
					server.overworld().getGameTime(), Goals.stagesCleared(), finished);
		} catch (RuntimeException e) {
			Bubble.LOG.error("Could not pay out Eidolons", e);
		}
		config.run++;
		config.livesUsed = 0;
		String seed = retry ? currentSeed : config.nextSeed;
		if (!retry) {
			config.nextSeed = "";
		}
		config.save(configPath);
		WorldArchiver.request(finished, seed);
		Component msg = Component.literal((retry ? "Retrying the same seed! " : "Loading the next world! ")
				+ "Rejoin in about a minute for run #" + config.run + ".");
		for (ServerPlayer q : players()) {
			q.connection.disconnect(msg);
		}
		Bubble.LOG.info("Run #{} over; stopping so the panel can start run #{} ({})", finished, config.run,
				retry ? "same seed " + seed : seed.isEmpty() ? "new random seed" : "seed " + seed);
		server.halt(false);
	}

	// ------------------------------------------------------------------ another life

	/** The newest safe spot from at least 3 seconds before `before` (so you don't land back in the lava). */
	private static Safe safeSpot(UUID id, long before) {
		ArrayDeque<Safe> spots = safeSpots.get(id);
		if (spots == null || spots.isEmpty()) {
			return null;
		}
		Safe pick = null;
		for (Safe s : spots) {
			if (s.tick() <= before - 60) {
				pick = s;
			}
		}
		return pick != null ? pick : spots.peekFirst();
	}

	private static void saveRestorePoints() {
		restorePoints.clear();
		for (ServerPlayer q : players()) {
			Capture c = deathCaptures.get(q.getUUID());
			if (c != null) {
				Safe safe = safeSpot(q.getUUID(), c.tick());
				if (safe != null) {
					restorePoints.put(q.getUUID(), new Restore(safe.dim(), safe.pos(), safe.yaw(), safe.pitch(), c.mode(), c));
				} else {
					ServerLevel w = server.overworld();
					Vec3 spawn = net.minecraft.world.phys.Vec3.atBottomCenterOf(w.getSharedSpawnPos());
					restorePoints.put(q.getUUID(), new Restore(w.dimension(), spawn, 0, 0, c.mode(), c));
				}
			} else {
				restorePoints.put(q.getUUID(), new Restore(q.level().dimension(), q.position(), q.getYRot(), q.getXRot(),
						q.gameMode.getGameModeForPlayer(), null));
			}
		}
	}

	/** Everyone goes back to how they were (the dead to a safe spot from just before), with full health. */
	private static void anotherLife() {
		phase = Phase.PLAYING;
		config.livesUsed++;
		config.save(configPath);
		votes.clear();
		postGame = null;
		sharedHealth = -1;
		lastHealth.clear();
		tracks.clear();
		ReplayScene.clear();
		stuck.clear();
		Component title = Component.literal("❤ Another life").withStyle(ChatFormatting.LIGHT_PURPLE);
		Component sub = Component.literal("Life #" + (config.livesUsed + 1) + " of run #" + config.run).withStyle(ChatFormatting.GRAY);
		for (ServerPlayer q : players()) {
			q.setCamera(q);
			Restore r = restorePoints.get(q.getUUID());
			if (r != null) {
				ServerLevel w = server.getLevel(r.dim());
				if (w == null) {
					w = server.overworld();
				}
				q.setGameMode(r.mode() == GameType.SPECTATOR ? GameType.SURVIVAL : r.mode());
				Capture c = r.capture();
				if (c != null) {
					q.getInventory().clearContent();
					q.getInventory().load(c.inventory());
					AccessoryCompat.restore(q, c.curios());
					q.setExperienceLevels(c.level());
					q.experienceProgress = c.progress();
					q.totalExperience = c.total();
					q.getFoodData().setFoodLevel(Math.max(c.food(), 6));
					q.getFoodData().setSaturation(c.saturation());
				}
				q.teleportTo(w, r.pos().x, r.pos().y, r.pos().z, r.yaw(), r.pitch());
			} else {
				q.setGameMode(GameType.SURVIVAL); // joined during the post-game: just let them play
			}
			q.setHealth(q.getMaxHealth());
			q.clearFire();
			q.fallDistance = 0;
			q.setDeltaMovement(Vec3.ZERO);
			q.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 100, 4));
			q.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200, 0));
			q.containerMenu.broadcastChanges();
			title(q, title, sub, 5, 50, 15);
			if (ServerPlayNetworking.canSend(q, Payloads.PostGameEnd.ID)) {
				ServerPlayNetworking.send(q, new Payloads.PostGameEnd());
			}
			q.playNotifySound(SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.6f, 1.2f);
		}
		restorePoints.clear();
		deathCaptures.clear();
		server.getPlayerList().broadcastSystemMessage(Component.literal("❤ Another life! Everyone is back where they were. Lives used this run: "
				+ config.livesUsed).withStyle(ChatFormatting.LIGHT_PURPLE), false);
		broadcastState();
	}

	private static void onRespawn(ServerPlayer p) {
		if (phase == Phase.KILLCAM) {
			bringToKillcam(p);
		} else if (phase == Phase.POSTGAME || phase == Phase.STOPPING) {
			p.setGameMode(GameType.SPECTATOR);
		} else if (config.soulLink && sharedHealth > 0) {
			p.setHealth(Math.min(sharedHealth, p.getMaxHealth()));
			lastHealth.put(p.getUUID(), p.getHealth());
		}
	}

	private static void onJoin(ServerPlayer p) {
		broadcastState();
		Matter.sendBalance(p);
		if (phase == Phase.KILLCAM) {
			bringToKillcam(p);
		} else if (phase == Phase.POSTGAME) {
			p.setGameMode(GameType.SPECTATOR);
			sendPostGame(p);
		}
	}

	// ------------------------------------------------------------------ stats

	private static Payloads.PlayerStats statsFor(ServerPlayer p) {
		ServerStatsCounter s = p.getStats();
		long mined = 0;
		for (Block b : BuiltInRegistries.BLOCK) {
			mined += s.getValue(Stats.BLOCK_MINED, b);
		}
		long crafted = 0;
		for (Item i : BuiltInRegistries.ITEM) {
			crafted += s.getValue(Stats.ITEM_CRAFTED, i);
		}
		long distance = 0;
		for (ResourceLocation id : new ResourceLocation[]{Stats.WALK_ONE_CM, Stats.SPRINT_ONE_CM, Stats.CROUCH_ONE_CM, Stats.SWIM_ONE_CM,
				Stats.WALK_ON_WATER_ONE_CM, Stats.WALK_UNDER_WATER_ONE_CM, Stats.FLY_ONE_CM, Stats.AVIATE_ONE_CM, Stats.BOAT_ONE_CM,
				Stats.HORSE_ONE_CM, Stats.MINECART_ONE_CM, Stats.PIG_ONE_CM, Stats.STRIDER_ONE_CM, Stats.CLIMB_ONE_CM}) {
			distance += s.getValue(Stats.CUSTOM, id);
		}
		return new Payloads.PlayerStats(p.getName().getString(), clampInt(mined), s.getValue(Stats.CUSTOM, Stats.MOB_KILLS),
				s.getValue(Stats.CUSTOM, Stats.DAMAGE_DEALT), s.getValue(Stats.CUSTOM, Stats.DAMAGE_TAKEN), clampInt(distance),
				clampInt(crafted), s.getValue(Stats.CUSTOM, Stats.DEATHS));
	}

	private static int clampInt(long v) {
		return (int) Math.min(Integer.MAX_VALUE, Math.max(0, v));
	}

	/** Chat version of the post-game, for everyone (players without the mod only get this). */
	private static void broadcastSummary(List<Payloads.PlayerStats> stats, int durationTicks) {
		var pm = server.getPlayerList();
		pm.broadcastSystemMessage(Component.literal("— Run #" + config.run + " summary (" + formatDuration(durationTicks) + ") —").withStyle(ChatFormatting.GOLD), false);
		highlight(stats, "Most blocks mined", Payloads.PlayerStats::blocksMined, "");
		highlight(stats, "Most mob kills", Payloads.PlayerStats::mobKills, "");
		highlight(stats, "Most damage dealt", p -> p.damageDealt() / 20, " hearts");
		highlight(stats, "Furthest travelled", p -> p.distanceCm() / 100, " blocks");
		pm.broadcastSystemMessage(Component.literal("Vote on the summary screen (or /bubble vote next|retry|life): next world, retry this seed, or another life. "
				+ "60s left; when everyone has voted it happens right away.").withStyle(ChatFormatting.GRAY), false);
	}

	private static void highlight(List<Payloads.PlayerStats> stats, String label, ToIntFunction<Payloads.PlayerStats> value, String unit) {
		stats.stream().max(Comparator.comparingInt(value)).filter(p -> value.applyAsInt(p) > 0).ifPresent(p ->
				server.getPlayerList().broadcastSystemMessage(Component.literal(label + ": ").withStyle(ChatFormatting.GRAY)
						.append(Component.literal(p.name() + " (" + value.applyAsInt(p) + unit + ")").withStyle(ChatFormatting.WHITE)), false));
	}

	static String formatDuration(int ticks) {
		int secs = ticks / 20;
		int h = secs / 3600;
		int m = (secs % 3600) / 60;
		return h > 0 ? h + "h " + m + "m" : m + "m " + (secs % 60) + "s";
	}

	// ------------------------------------------------------------------ settings + screen sync

	private static void onUpdate(ServerPlayer player, Payloads.Update u) {
		if (!player.hasPermissions(2)) {
			sendState(player, server.getPlayerList().getPlayerCount());
			return;
		}
		apply(u.bubbleEnabled(), u.pushMode(), u.soulLink(), u.hardcoreReset(), u.vertical(), u.radius(), u.wrapMode(), player.getName().getString());
	}

	static void apply(boolean bubble, boolean push, boolean soulLink, boolean hardcore, boolean vertical, int radius, String by) {
		apply(bubble, push, soulLink, hardcore, vertical, radius, config.wrapMode, by);
	}

	static void apply(boolean bubble, boolean push, boolean soulLink, boolean hardcore, boolean vertical, int radius, boolean wrap, String by) {
		boolean changed = config.bubbleEnabled != bubble || config.pushMode != push || config.soulLink != soulLink
				|| config.hardcoreReset != hardcore || config.vertical != vertical || config.radius != radius || config.wrapMode != wrap;
		config.wrapMode = wrap;
		config.vertical = vertical;
		config.bubbleEnabled = bubble;
		config.pushMode = push;
		config.soulLink = soulLink;
		config.hardcoreReset = hardcore;
		config.radius = radius;
		config.clamp();
		config.save(configPath);
		if (server != null) {
			server.getGameRules().getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(config.hardcoreReset, server);
		}
		broadcastState();
		if (changed && server != null) {
			server.getPlayerList().broadcastSystemMessage(Component.literal(by + " changed Bubble: ").withStyle(ChatFormatting.GRAY).append(statusText()), false);
		}
	}

	static void broadcastState() {
		if (server == null) {
			return;
		}
		int count = server.getPlayerList().getPlayerCount();
		for (ServerPlayer p : players()) {
			sendState(p, count);
		}
	}

	private static void sendState(ServerPlayer p, int count) {
		if (ServerPlayNetworking.canSend(p, Payloads.State.ID)) {
			ServerPlayNetworking.send(p, new Payloads.State(config.bubbleEnabled, config.pushMode, config.soulLink,
					config.hardcoreReset, config.vertical, config.radius, p.hasPermissions(2), count, config.run, config.wrapMode));
		}
	}

	private static void hideField(ServerPlayer p) {
		if (fieldShown.remove(p.getUUID()) && ServerPlayNetworking.canSend(p, Payloads.Field.ID)) {
			ServerPlayNetworking.send(p, Payloads.Field.OFF);
		}
	}

	/** Opens the settings screen (or the post-game summary) for players who have the mod. */
	static boolean openFor(ServerPlayer p) {
		if (!ServerPlayNetworking.canSend(p, Payloads.Open.ID)) {
			return false;
		}
		if (phase == Phase.POSTGAME) {
			sendPostGame(p);
			return true;
		}
		sendState(p, server.getPlayerList().getPlayerCount());
		ServerPlayNetworking.send(p, new Payloads.Open());
		return true;
	}

	static String currentSeed() {
		return currentSeed;
	}

	static void saveConfig() {
		config.save(configPath);
	}

	static String edgeName() {
		return config.wrapMode ? "wrap" : config.pushMode ? "push" : "teleport";
	}

	static Component statusText() {
		return Component.literal("bubble " + (config.bubbleEnabled ? "on" : "off") + ", radius " + config.radius
				+ ", " + (config.vertical ? "sphere" : "ring") + ", edge " + edgeName() + ", soul link " + (config.soulLink ? "on" : "off")
				+ ", hardcore reset " + (config.hardcoreReset ? "on" : "off") + ", run #" + config.run).withStyle(ChatFormatting.WHITE);
	}

	static void title(ServerPlayer p, Component title, Component subtitle, int in, int stay, int out) {
		p.connection.send(new ClientboundSetTitlesAnimationPacket(in, stay, out));
		p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
		p.connection.send(new ClientboundSetTitleTextPacket(title));
	}

	static int setRadius(int radius, CommandSourceStack source) {
		apply(config.bubbleEnabled, config.pushMode, config.soulLink, config.hardcoreReset, config.vertical, radius, source.getTextName());
		return 1;
	}
}
