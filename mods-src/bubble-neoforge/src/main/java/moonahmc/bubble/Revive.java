package moonahmc.bubble;

import moonahmc.bubble.net.PayloadTypeRegistry;
import moonahmc.bubble.net.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Going down instead of dying. A hit that would kill you knocks you down: you crawl, can't fight or use things, nothing
 * can hurt you and mobs lose interest. A REVIVE sign floats over your head (yellow, going red as time runs out). A
 * teammate standing next to you for a few seconds gets you back up (the clock stops while they do); if the time runs
 * out you die for real, which then counts like any other death (hardcore reset, kill cam...).
 * <p>
 * No going down when: revive is off, soul link is on (one health bar), nobody else is up to revive you, the run isn't
 * being played, or it was the void / a kill command. Settings: config/bubble.json (reviveEnabled, reviveSeconds,
 * reviveHoldSeconds), the Bubble menu's Settings tab (ops), or /bubble revive.
 */
public final class Revive {
	/** How close a teammate has to stand (blocks, sideways; 2 up or down). */
	private static final double REACH = 2.5;
	private static final ResourceLocation SLOW = ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "downed_slow");
	private static final ResourceLocation NO_JUMP = ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "downed_no_jump");

	private static final class Down {
		final DamageSource source;
		int left;
		int progress;
		String helper = "";

		Down(DamageSource source, int left) {
			this.source = source;
			this.left = left;
		}
	}

	private static final Map<UUID, Down> downed = new LinkedHashMap<>();
	/** Players whose next death is a real one (their time ran out, or a kill that skips going down). */
	private static final Set<UUID> bypass = new HashSet<>();
	/** Left the game while down: they bleed out when they come back. */
	private static final Set<UUID> leftWhileDown = new HashSet<>();
	private static MinecraftServer server;
	private static boolean sentEmpty = true;

	private Revive() {
	}

	// ------------------------------------------------------------------ packets

	public record Entry(int entityId, String name, int left, int total, float progress, String helper) {
	}

	/** Server to client, a few times a second while anyone is down: who, how long they've got, revive progress. */
	public record Downed(List<Entry> list) implements CustomPacketPayload {
		public static final Type<Downed> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "downed"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Downed> CODEC = StreamCodec.ofMember((d, buf) -> {
			buf.writeVarInt(d.list.size());
			for (Entry e : d.list) {
				buf.writeVarInt(e.entityId);
				buf.writeUtf(e.name, 32);
				buf.writeVarInt(e.left);
				buf.writeVarInt(e.total);
				buf.writeFloat(e.progress);
				buf.writeUtf(e.helper, 32);
			}
		}, buf -> {
			int n = Math.min(buf.readVarInt(), 100);
			List<Entry> list = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				list.add(new Entry(buf.readVarInt(), buf.readUtf(32), buf.readVarInt(), buf.readVarInt(), buf.readFloat(), buf.readUtf(32)));
			}
			return new Downed(list);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Server to client: the revive settings (for the Bubble menu). */
	public record Settings(boolean enabled, int seconds, int holdSeconds, boolean canEdit) implements CustomPacketPayload {
		public static final Type<Settings> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "revive_settings"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Settings> CODEC = StreamCodec.ofMember((s, buf) -> {
			buf.writeBoolean(s.enabled);
			buf.writeVarInt(s.seconds);
			buf.writeVarInt(s.holdSeconds);
			buf.writeBoolean(s.canEdit);
		}, buf -> new Settings(buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client to server: an op changed the revive settings. */
	public record Edit(boolean enabled, int seconds, int holdSeconds) implements CustomPacketPayload {
		public static final Type<Edit> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "revive_edit"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Edit> CODEC = StreamCodec.ofMember((e, buf) -> {
			buf.writeBoolean(e.enabled);
			buf.writeVarInt(e.seconds);
			buf.writeVarInt(e.holdSeconds);
		}, buf -> new Edit(buf.readBoolean(), buf.readVarInt(), buf.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	static void register(PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c, PayloadTypeRegistry<RegistryFriendlyByteBuf> c2s) {
		s2c.register(Downed.ID, Downed.CODEC);
		s2c.register(Settings.ID, Settings.CODEC);
		c2s.register(Edit.ID, Edit.CODEC);
	}

	// ------------------------------------------------------------------ setup

	static void init() {
		var bus = NeoForge.EVENT_BUS;
		bus.addListener((ServerStartedEvent e) -> server = e.getServer());
		bus.addListener((ServerStoppedEvent e) -> {
			server = null;
			downed.clear();
			bypass.clear();
			leftWhileDown.clear();
		});
		bus.addListener((ServerTickEvent.Post e) -> tick(e.getServer()));
		// Nothing hurts you while you're down (except the void or a kill command, which end it)
		bus.addListener(EventPriority.HIGHEST, (LivingIncomingDamageEvent e) -> {
			if (e.getEntity() instanceof ServerPlayer p && downed.containsKey(p.getUUID()) && !skipsDown(e.getSource())) {
				e.setCanceled(true);
			}
		});
		// ...and you can't fight, use things or break blocks
		bus.addListener((AttackEntityEvent e) -> {
			if (isDown(e.getEntity().getUUID())) {
				e.setCanceled(true);
			}
		});
		bus.addListener((PlayerInteractEvent.RightClickItem e) -> {
			if (!e.getLevel().isClientSide && isDown(e.getEntity().getUUID())) {
				e.setCanceled(true);
			}
		});
		bus.addListener((PlayerInteractEvent.RightClickBlock e) -> {
			if (!e.getLevel().isClientSide && isDown(e.getEntity().getUUID())) {
				e.setCanceled(true);
			}
		});
		bus.addListener((PlayerInteractEvent.EntityInteract e) -> {
			if (!e.getLevel().isClientSide && isDown(e.getEntity().getUUID())) {
				e.setCanceled(true);
			}
		});
		bus.addListener((PlayerInteractEvent.LeftClickBlock e) -> {
			if (!e.getLevel().isClientSide && isDown(e.getEntity().getUUID())) {
				e.setCanceled(true);
			}
		});
		bus.addListener((BlockEvent.BreakEvent e) -> {
			if (isDown(e.getPlayer().getUUID())) {
				e.setCanceled(true);
			}
		});
		bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
			if (e.getEntity() instanceof ServerPlayer p) {
				p.server.execute(() -> {
					sendSettings(p);
					if (leftWhileDown.remove(p.getUUID())) {
						p.sendSystemMessage(Component.literal("You left while you were down, so you bled out.").withStyle(ChatFormatting.RED));
						kill(p, p.damageSources().generic());
					}
				});
			}
		});
		bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
			if (e.getEntity() instanceof ServerPlayer p && downed.remove(p.getUUID()) != null) {
				leftWhileDown.add(p.getUUID());
				getUp(p);
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(Edit.ID, (payload, context) -> context.server().execute(() -> {
			if (context.player().hasPermissions(2)) {
				set(payload.enabled(), payload.seconds(), payload.holdSeconds(), context.player().getGameProfile().getName());
			} else {
				sendSettings(context.player());
			}
		}));
	}

	static boolean isDown(UUID id) {
		return downed.containsKey(id);
	}

	private static boolean skipsDown(DamageSource source) {
		return source.is(DamageTypes.FELL_OUT_OF_WORLD) || source.is(DamageTypes.GENERIC_KILL);
	}

	// ------------------------------------------------------------------ going down

	/** From the LivingDeathEvent, before anything else: true = knocked down instead (the death is cancelled). */
	static boolean tryDown(ServerPlayer p, DamageSource source) {
		if (bypass.remove(p.getUUID())) {
			return false;
		}
		BubbleConfig c = BubbleServer.config;
		if (server == null || c == null || !c.reviveEnabled || c.soulLink || !BubbleServer.playing() || Lobby.active()
				|| p.isSpectator() || p.isCreative() || downed.containsKey(p.getUUID()) || skipsDown(source)) {
			return false;
		}
		boolean someoneUp = server.getPlayerList().getPlayers().stream()
				.anyMatch(q -> q != p && BubbleServer.counts(q) && !downed.containsKey(q.getUUID()));
		if (!someoneUp) {
			return false; // nobody to revive you
		}
		p.setHealth(1);
		p.stopRiding();
		p.stopFallFlying();
		p.setSprinting(false);
		p.clearFire();
		p.resetFallDistance();
		p.setForcedPose(Pose.SWIMMING);
		downed.put(p.getUUID(), new Down(source, c.reviveSeconds * 20));
		hobble(p, true);
		server.getPlayerList().broadcastSystemMessage(Component.literal("✚ ").withStyle(ChatFormatting.YELLOW)
				.append(p.getDisplayName()).append(Component.literal(" is down! Stand next to them to revive them (" + c.reviveSeconds + "s)")
						.withStyle(ChatFormatting.YELLOW)), false);
		for (ServerPlayer q : server.getPlayerList().getPlayers()) {
			q.playNotifySound(SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 0.8f, 0.6f);
		}
		RunLog.add(RunLog.ADMIN, p.getGameProfile().getName() + " went down (" + source.getLocalizedDeathMessage(p).getString() + ")");
		broadcast();
		return true;
	}

	/** Slow crawl, no jumping (on while down, off when you get up). */
	private static void hobble(ServerPlayer p, boolean on) {
		var speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		var jump = p.getAttribute(Attributes.JUMP_STRENGTH);
		if (on) {
			if (speed != null) {
				speed.addOrUpdateTransientModifier(new AttributeModifier(SLOW, -0.65, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			}
			if (jump != null) {
				jump.addOrUpdateTransientModifier(new AttributeModifier(NO_JUMP, -1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			}
		} else {
			if (speed != null) {
				speed.removeModifier(SLOW);
			}
			if (jump != null) {
				jump.removeModifier(NO_JUMP);
			}
		}
	}

	private static void getUp(ServerPlayer p) {
		p.setForcedPose(null);
		hobble(p, false);
	}

	private static void tick(MinecraftServer s) {
		if (downed.isEmpty()) {
			if (!sentEmpty) {
				broadcast();
			}
			return;
		}
		if (!BubbleServer.playing() || Lobby.active()) {
			// the run ended (or a new one is starting): everyone just gets up
			for (UUID id : new ArrayList<>(downed.keySet())) {
				ServerPlayer p = s.getPlayerList().getPlayer(id);
				downed.remove(id);
				if (p != null) {
					getUp(p);
				}
			}
			broadcast();
			return;
		}
		int hold = Math.max(0, BubbleServer.config.reviveHoldSeconds) * 20;
		// Everyone is down: nobody can revive anyone, so it's over now
		boolean someoneUp = s.getPlayerList().getPlayers().stream().anyMatch(q -> BubbleServer.counts(q) && !downed.containsKey(q.getUUID()));
		for (Map.Entry<UUID, Down> e : new ArrayList<>(downed.entrySet())) {
			ServerPlayer p = s.getPlayerList().getPlayer(e.getKey());
			Down d = e.getValue();
			if (p == null) {
				continue;
			}
			if (!someoneUp) {
				bleedOut(p, d);
				continue;
			}
			// keep them down: crawling, at 1 health, not burning
			p.setForcedPose(Pose.SWIMMING);
			p.clearFire();
			if (p.getHealth() < 1) {
				p.setHealth(1);
			}
			ServerPlayer helper = helperFor(p);
			if (helper != null) {
				d.helper = helper.getGameProfile().getName();
				d.progress++;
				if (d.progress >= hold) {
					revive(p, helper);
					continue;
				}
			} else {
				d.helper = "";
				d.progress = Math.max(0, d.progress - 2);
				if (--d.left <= 0) {
					bleedOut(p, d);
					continue;
				}
			}
			if (s.getTickCount() % 10 == 0) {
				// mobs forget about you
				for (Mob m : p.serverLevel().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(40), m -> m.getTarget() == p)) {
					m.setTarget(null);
				}
			}
		}
		if (s.getTickCount() % 4 == 0) {
			broadcast();
		}
	}

	/** The nearest teammate who's up, alive and close enough to revive this player, or null. */
	private static ServerPlayer helperFor(ServerPlayer p) {
		ServerPlayer best = null;
		double bestD = Double.MAX_VALUE;
		for (ServerPlayer q : p.serverLevel().players()) {
			if (q == p || !BubbleServer.counts(q) || downed.containsKey(q.getUUID())) {
				continue;
			}
			double dx = q.getX() - p.getX();
			double dz = q.getZ() - p.getZ();
			double flat = dx * dx + dz * dz;
			if (flat <= REACH * REACH && Math.abs(q.getY() - p.getY()) <= 2 && flat < bestD) {
				best = q;
				bestD = flat;
			}
		}
		return best;
	}

	private static void revive(ServerPlayer p, ServerPlayer helper) {
		downed.remove(p.getUUID());
		getUp(p);
		p.setHealth(Math.min(p.getMaxHealth(), 6));
		p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 0));
		p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 60, 3));
		p.invulnerableTime = 40;
		server.getPlayerList().broadcastSystemMessage(Component.literal("✚ ").withStyle(ChatFormatting.GREEN)
				.append(helper.getDisplayName()).append(Component.literal(" revived ").withStyle(ChatFormatting.GREEN))
				.append(p.getDisplayName()).append(Component.literal("!").withStyle(ChatFormatting.GREEN)), false);
		p.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.4f);
		helper.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.4f);
		RunLog.add(RunLog.ADMIN, helper.getGameProfile().getName() + " revived " + p.getGameProfile().getName());
		broadcast();
	}

	private static void bleedOut(ServerPlayer p, Down d) {
		downed.remove(p.getUUID());
		getUp(p);
		p.sendSystemMessage(Component.literal("Nobody got to you in time.").withStyle(ChatFormatting.RED));
		kill(p, d.source);
		broadcast();
	}

	/** A real death with the original cause (so the death message and the kill cam are right). */
	private static void kill(ServerPlayer p, DamageSource source) {
		bypass.add(p.getUUID());
		p.setHealth(0);
		p.die(source);
		bypass.remove(p.getUUID());
	}

	private static void broadcast() {
		if (server == null) {
			return;
		}
		int total = Math.max(1, BubbleServer.config.reviveSeconds * 20);
		int hold = Math.max(1, BubbleServer.config.reviveHoldSeconds * 20);
		List<Entry> list = new ArrayList<>();
		for (Map.Entry<UUID, Down> e : downed.entrySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p != null) {
				Down d = e.getValue();
				list.add(new Entry(p.getId(), p.getGameProfile().getName(), d.left, total, Math.min(1f, d.progress / (float) hold), d.helper));
			}
		}
		sentEmpty = list.isEmpty();
		Downed packet = new Downed(list);
		for (ServerPlayer q : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(q, Downed.ID)) {
				ServerPlayNetworking.send(q, packet);
			}
		}
	}

	// ------------------------------------------------------------------ settings

	static void sendSettings(ServerPlayer p) {
		BubbleConfig c = BubbleServer.config;
		if (c != null && ServerPlayNetworking.canSend(p, Settings.ID)) {
			ServerPlayNetworking.send(p, new Settings(c.reviveEnabled, c.reviveSeconds, c.reviveHoldSeconds, p.hasPermissions(2)));
		}
	}

	static void set(boolean enabled, int seconds, int hold, String by) {
		BubbleConfig c = BubbleServer.config;
		boolean changed = c.reviveEnabled != enabled || c.reviveSeconds != seconds || c.reviveHoldSeconds != hold;
		c.reviveEnabled = enabled;
		c.reviveSeconds = seconds;
		c.reviveHoldSeconds = hold;
		c.clamp();
		BubbleServer.saveConfig();
		if (server != null) {
			server.getPlayerList().getPlayers().forEach(Revive::sendSettings);
			if (changed) {
				server.getPlayerList().broadcastSystemMessage(Component.literal(by + " changed revive: ").withStyle(ChatFormatting.GRAY)
						.append(Component.literal(c.reviveEnabled ? "on, " + c.reviveSeconds + "s to bleed out, " + c.reviveHoldSeconds + "s to revive" : "off")
								.withStyle(c.reviveEnabled ? ChatFormatting.GREEN : ChatFormatting.RED)), false);
			}
		}
	}

	/** /bubble revive [on|off] [seconds] */
	static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> command() {
		var seconds = com.mojang.brigadier.arguments.IntegerArgumentType.integer(BubbleConfig.MIN_REVIVE_SECONDS, BubbleConfig.MAX_REVIVE_SECONDS);
		return net.minecraft.commands.Commands.literal("revive").requires(s -> s.hasPermission(2))
				.executes(c -> {
					BubbleConfig cfg = BubbleServer.config;
					c.getSource().sendSuccess(() -> Component.literal("Revive is " + (cfg.reviveEnabled ? "on: " + cfg.reviveSeconds
							+ "s to bleed out, " + cfg.reviveHoldSeconds + "s standing next to someone to revive them" : "off")
							+ ". /bubble revive on|off [seconds]"), false);
					return 1;
				})
				.then(net.minecraft.commands.Commands.literal("on").executes(c -> setFrom(c.getSource(), true, BubbleServer.config.reviveSeconds))
						.then(net.minecraft.commands.Commands.argument("seconds", seconds)
								.executes(c -> setFrom(c.getSource(), true, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "seconds")))))
				.then(net.minecraft.commands.Commands.literal("off").executes(c -> setFrom(c.getSource(), false, BubbleServer.config.reviveSeconds)));
	}

	private static int setFrom(CommandSourceStack src, boolean on, int secs) {
		set(on, secs, BubbleServer.config.reviveHoldSeconds, src.getTextName());
		src.sendSuccess(() -> Component.literal("Revive " + (on ? "on (" + BubbleServer.config.reviveSeconds + "s)" : "off")), true);
		return 1;
	}
}
