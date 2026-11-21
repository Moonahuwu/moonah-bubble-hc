package moonahmc.bubble;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import moonahmc.bubble.net.PayloadTypeRegistry;
import moonahmc.bubble.net.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Ready-up lobby at the start of a new run. Until everyone online has pressed Ready (or an op presses Start), players
 * wait near spawn in adventure mode and can't be hurt, so latecomers can still join. Then there's a 5-second countdown,
 * everyone goes back to survival and the start-of-run wheel spins.
 */
public final class Lobby {
	private static final int COUNTDOWN_TICKS = 100;
	private static final double LEASH = 24;

	/** Server to client: lobby state (active = false closes the ready screen). */
	public record State(boolean active, List<String> names, List<Boolean> ready, int countdown, boolean canStart, String info) implements CustomPacketPayload {
		public static final Type<State> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "lobby_state"));
		public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.ofMember((s, buf) -> {
			buf.writeBoolean(s.active);
			buf.writeVarInt(s.names.size());
			for (int i = 0; i < s.names.size(); i++) {
				buf.writeUtf(s.names.get(i), 32);
				buf.writeBoolean(s.ready.get(i));
			}
			buf.writeVarInt(s.countdown);
			buf.writeBoolean(s.canStart);
			buf.writeUtf(s.info, 512);
		}, buf -> {
			boolean active = buf.readBoolean();
			int n = Math.min(buf.readVarInt(), 200);
			List<String> names = new ArrayList<>();
			List<Boolean> ready = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				names.add(buf.readUtf(32));
				ready.add(buf.readBoolean());
			}
			return new State(active, names, ready, buf.readVarInt(), buf.readBoolean(), buf.readUtf(512));
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client to server: I'm ready (or not). */
	public record Ready(boolean ready) implements CustomPacketPayload {
		public static final Type<Ready> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "lobby_ready"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Ready> CODEC = StreamCodec.ofMember((r, buf) -> buf.writeBoolean(r.ready),
				buf -> new Ready(buf.readBoolean()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client to server (ops): an action from the Admin tab or an op's shift-click on the post-game vote. */
	public record Admin(int action) implements CustomPacketPayload {
		public static final Type<Admin> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "admin_action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Admin> CODEC = StreamCodec.ofMember((a, buf) -> buf.writeVarInt(a.action),
				buf -> new Admin(buf.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	private static boolean active;
	private static final Set<UUID> ready = new HashSet<>();
	private static long countdownEnd = -1;
	private static Path stateFile;
	private static MinecraftServer server;

	private Lobby() {
	}

	static void register(PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c, PayloadTypeRegistry<RegistryFriendlyByteBuf> c2s) {
		s2c.register(State.ID, State.CODEC);
		c2s.register(Ready.ID, Ready.CODEC);
		c2s.register(Admin.ID, Admin.CODEC);
	}

	public static boolean active() {
		return active;
	}

	static void init() {
		var bus = NeoForge.EVENT_BUS;
		bus.addListener((ServerStartedEvent e) -> {
			server = e.getServer();
			stateFile = server.getWorldPath(LevelResource.ROOT).resolve("bubble-lobby.json");
			ready.clear();
			countdownEnd = -1;
			active = false;
			try {
				if (Files.exists(stateFile)) {
					JsonObject o = JsonParser.parseString(Files.readString(stateFile)).getAsJsonObject();
					active = o.has("active") && o.get("active").getAsBoolean();
				} else if (server.overworld().getGameTime() < 1200 && Pregame.lobbyEnabled()) {
					active = true; // a brand-new world: wait for everyone
					save();
				}
			} catch (Exception ex) {
				Bubble.LOG.error("Could not read {}", stateFile, ex);
			}
			if (active) {
				Bubble.LOG.info("Lobby: waiting for players to ready up");
			}
		});
		bus.addListener((ServerStoppedEvent e) -> server = null);
		bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
			if (active && e.getEntity() instanceof ServerPlayer p) {
				p.server.execute(() -> {
					hold(p, true);
					p.sendSystemMessage(Component.literal("Waiting for everyone to ready up. Press B to open the ready screen.")
							.withStyle(ChatFormatting.YELLOW));
					broadcast();
				});
			}
		});
		bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
			if (active) {
				ready.remove(e.getEntity().getUUID());
				e.getEntity().getServer().execute(Lobby::check);
			}
		});
		bus.addListener((LivingIncomingDamageEvent e) -> {
			if (active && e.getEntity() instanceof ServerPlayer) {
				e.setCanceled(true);
			}
		});
		bus.addListener((ServerTickEvent.Post e) -> {
			if (!active) {
				return;
			}
			long t = e.getServer().getTickCount();
			if (t % 10 == 0) {
				e.getServer().getPlayerList().getPlayers().forEach(p -> hold(p, false));
			}
			if (countdownEnd >= 0) {
				long left = countdownEnd - t;
				if (left <= 0) {
					start(null);
				} else if (left % 20 == 0) {
					for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) {
						BubbleServer.title(p, Component.literal(String.valueOf(left / 20)).withStyle(ChatFormatting.GOLD), Component.empty(), 0, 25, 0);
						p.playNotifySound(SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 1f, 1.2f);
					}
					broadcast();
				}
			}
		});
	}

	/** Keeps a waiting player safe: adventure mode, fed, and near spawn. */
	private static void hold(ServerPlayer p, boolean joined) {
		if (p.gameMode.getGameModeForPlayer() == GameType.SURVIVAL) {
			p.setGameMode(GameType.ADVENTURE);
		}
		if (p.gameMode.getGameModeForPlayer() != GameType.ADVENTURE) {
			return; // creative / spectator ops roam free
		}
		p.getFoodData().setFoodLevel(20);
		if (p.getHealth() < p.getMaxHealth()) {
			p.setHealth(p.getMaxHealth());
		}
		p.clearFire();
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		if (joined || p.level() != level || p.distanceToSqr(spawn.getX() + 0.5, p.getY(), spawn.getZ() + 0.5) > LEASH * LEASH) {
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ());
			p.teleportTo(level, spawn.getX() + 0.5, y, spawn.getZ() + 0.5, p.getYRot(), p.getXRot());
			p.fallDistance = 0;
		}
	}

	static void setReady(ServerPlayer p, boolean r) {
		if (!active) {
			return;
		}
		if (r ? ready.add(p.getUUID()) : ready.remove(p.getUUID())) {
			p.server.getPlayerList().broadcastSystemMessage(Component.literal(p.getGameProfile().getName() + (r ? " is ready" : " isn't ready"))
					.withStyle(r ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
		}
		check();
	}

	/** Everyone online ready = start the countdown; someone not ready (or a new player) = stop it. */
	private static void check() {
		if (!active || server == null) {
			return;
		}
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		boolean all = !players.isEmpty() && players.stream().allMatch(p -> ready.contains(p.getUUID()));
		if (all && countdownEnd < 0) {
			countdownEnd = server.getTickCount() + COUNTDOWN_TICKS;
		} else if (!all && countdownEnd >= 0) {
			countdownEnd = -1;
			server.getPlayerList().broadcastSystemMessage(Component.literal("Countdown stopped: not everyone is ready.").withStyle(ChatFormatting.GRAY), false);
		}
		broadcast();
	}

	/** Starts the run now (an op's Start button, or the countdown ran out). */
	static boolean start(ServerPlayer by) {
		if (!active || server == null) {
			return false;
		}
		active = false;
		countdownEnd = -1;
		ready.clear();
		save();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) {
				p.setGameMode(GameType.SURVIVAL);
			}
			p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 60, 4));
			BubbleServer.title(p, Component.literal("Go!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
					Component.literal("Run #" + BubbleServer.config.run).withStyle(ChatFormatting.GRAY), 0, 30, 10);
			p.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1f);
		}
		server.getPlayerList().broadcastSystemMessage(Component.literal("▶ The run has started" + (by != null ? " (" + by.getGameProfile().getName() + " started it)" : "") + "!")
				.withStyle(ChatFormatting.GREEN), false);
		broadcast();
		Wheel.roundStarted();
		Eidolons.runStarted();
		Bubble.LOG.info("Lobby: run started");
		return true;
	}

	/** /bubble start: end the lobby and start the run (ops, or the server console). */
	static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> command() {
		return net.minecraft.commands.Commands.literal("start").requires(s -> s.hasPermission(2)).executes(c -> {
			if (!start(c.getSource().getPlayer())) {
				c.getSource().sendFailure(Component.literal("There's no lobby waiting."));
				return 0;
			}
			return 1;
		});
	}

	static void broadcast() {
		if (server == null) {
			return;
		}
		List<String> names = new ArrayList<>();
		List<Boolean> flags = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			names.add(p.getGameProfile().getName());
			flags.add(ready.contains(p.getUUID()));
		}
		Eidolons.sendBets();
		int countdown = countdownEnd < 0 ? -1 : (int) Math.max(0, (countdownEnd - server.getTickCount() + 19) / 20);
		String info = active ? runInfo() : "";
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(p, State.ID)) {
				ServerPlayNetworking.send(p, new State(active, names, flags, countdown, p.hasPermissions(2), info));
			}
		}
	}

	/** "Mode · Difficulty · Start wheel · Random events" for the ready-up screen. Parts are split with \n. */
	private static String runInfo() {
		List<String> parts = new ArrayList<>();
		String mode = Goals.currentModeName();
		if (!mode.isEmpty()) {
			parts.add(mode);
		}
		String diff = server.getWorldData().getDifficulty().getKey();
		parts.add(diff.substring(0, 1).toUpperCase() + diff.substring(1));
		if (Wheel.spinPending()) {
			parts.add("Start wheel spins when it starts");
		}
		if (RandomEvents.enabled()) {
			parts.add("Random events on");
		}
		return String.join("\n", parts);
	}

	private static void save() {
		if (stateFile == null) {
			return;
		}
		try {
			JsonObject o = new JsonObject();
			o.addProperty("active", active);
			Files.writeString(stateFile, o.toString());
		} catch (Exception e) {
			Bubble.LOG.error("Could not save {}", stateFile, e);
		}
	}
}
