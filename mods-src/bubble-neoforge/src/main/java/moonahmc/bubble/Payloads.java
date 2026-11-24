package moonahmc.bubble;

import moonahmc.bubble.net.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;

/** Network messages between the server (source of truth) and the client screens/renderers. */
public final class Payloads {
	private Payloads() {
	}

	private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> id(String path) {
		return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, path));
	}

	/** Server to client: current settings, whether this player may edit them, and info for the screen. */
	public record State(boolean bubbleEnabled, boolean pushMode, boolean soulLink, boolean hardcoreReset, boolean vertical,
						int radius, boolean canEdit, int players, int run) implements CustomPacketPayload {
		public static final Type<State> ID = id("state");
		public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.ofMember((s, buf) -> {
			buf.writeBoolean(s.bubbleEnabled);
			buf.writeBoolean(s.pushMode);
			buf.writeBoolean(s.soulLink);
			buf.writeBoolean(s.hardcoreReset);
			buf.writeBoolean(s.vertical);
			buf.writeVarInt(s.radius);
			buf.writeBoolean(s.canEdit);
			buf.writeVarInt(s.players);
			buf.writeVarInt(s.run);
		}, buf -> new State(buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
				buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client to server: an operator changed the settings. The server checks permission and clamps values. */
	public record Update(boolean bubbleEnabled, boolean pushMode, boolean soulLink, boolean hardcoreReset, boolean vertical,
						 int radius) implements CustomPacketPayload {
		public static final Type<Update> ID = id("update");
		public static final StreamCodec<RegistryFriendlyByteBuf, Update> CODEC = StreamCodec.ofMember((u, buf) -> {
			buf.writeBoolean(u.bubbleEnabled);
			buf.writeBoolean(u.pushMode);
			buf.writeBoolean(u.soulLink);
			buf.writeBoolean(u.hardcoreReset);
			buf.writeBoolean(u.vertical);
			buf.writeVarInt(u.radius);
		}, buf -> new Update(buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
				buf.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Server to client: open the settings screen (sent by /bubble). */
	public record Open() implements CustomPacketPayload {
		public static final Type<Open> ID = id("open");
		public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.unit(new Open());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Server to client, a few times a second: where the forcefield is. pulse = you were just pushed. */
	public record Field(boolean active, double centerX, double centerY, double centerZ, int radius, boolean sphere, boolean pulse)
			implements CustomPacketPayload {
		public static final Type<Field> ID = id("field");
		public static final Field OFF = new Field(false, 0, 0, 0, 0, false, false);
		public static final StreamCodec<RegistryFriendlyByteBuf, Field> CODEC = StreamCodec.ofMember((f, buf) -> {
			buf.writeBoolean(f.active);
			buf.writeDouble(f.centerX);
			buf.writeDouble(f.centerY);
			buf.writeDouble(f.centerZ);
			buf.writeVarInt(f.radius);
			buf.writeBoolean(f.sphere);
			buf.writeBoolean(f.pulse);
		}, buf -> new Field(buf.readBoolean(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readVarInt(),
				buf.readBoolean(), buf.readBoolean()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** One player's numbers for the post-game screen (taken from Minecraft's own statistics for this run). */
	public record PlayerStats(String name, int blocksMined, int mobKills, int damageDealt, int damageTaken,
							  int distanceCm, int itemsCrafted, int deaths) {
		static void write(RegistryFriendlyByteBuf buf, PlayerStats p) {
			buf.writeUtf(p.name);
			buf.writeVarInt(p.blocksMined);
			buf.writeVarInt(p.mobKills);
			buf.writeVarInt(p.damageDealt);
			buf.writeVarInt(p.damageTaken);
			buf.writeVarInt(p.distanceCm);
			buf.writeVarInt(p.itemsCrafted);
			buf.writeVarInt(p.deaths);
		}

		static PlayerStats read(RegistryFriendlyByteBuf buf) {
			return new PlayerStats(buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
					buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
		}
	}

	/** Server to client: the run is over; show the summary. */
	public record PostGame(int run, int durationTicks, String cause, List<PlayerStats> players, List<String> log) implements CustomPacketPayload {
		public static final Type<PostGame> ID = id("post_game");
		public static final StreamCodec<RegistryFriendlyByteBuf, PostGame> CODEC = StreamCodec.ofMember((g, buf) -> {
			buf.writeVarInt(g.run);
			buf.writeVarInt(g.durationTicks);
			buf.writeUtf(g.cause);
			buf.writeVarInt(g.players.size());
			for (PlayerStats p : g.players) {
				PlayerStats.write(buf, p);
			}
			int from = Math.max(0, g.log.size() - 300);
			buf.writeVarInt(g.log.size() - from);
			for (int i = from; i < g.log.size(); i++) {
				buf.writeUtf(g.log.get(i), 400);
			}
		}, buf -> {
			int run = buf.readVarInt();
			int duration = buf.readVarInt();
			String cause = buf.readUtf();
			int n = buf.readVarInt();
			List<PlayerStats> list = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				list.add(PlayerStats.read(buf));
			}
			int logs = Math.min(buf.readVarInt(), 300);
			List<String> log = new ArrayList<>(logs);
			for (int i = 0; i < logs; i++) {
				log.add(buf.readUtf(400));
			}
			return new PostGame(run, duration, cause, list, log);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Post-game vote options. */
	public static final int VOTE_NEXT = 0;
	public static final int VOTE_RETRY = 1;
	public static final int VOTE_LIFE = 2;

	/** Server to client, every second during the post-game: countdown, votes, and the seed-save count. */
	public record PostStatus(int secondsLeft, int total, int[] votes, int myVote, int seedSaves, boolean mySeedSave,
							 String seed, int livesUsed) implements CustomPacketPayload {
		public static final Type<PostStatus> ID = id("post_status");
		public static final StreamCodec<RegistryFriendlyByteBuf, PostStatus> CODEC = StreamCodec.ofMember((s, buf) -> {
			buf.writeVarInt(s.secondsLeft);
			buf.writeVarInt(s.total);
			for (int i = 0; i < 3; i++) {
				buf.writeVarInt(s.votes[i]);
			}
			buf.writeVarInt(s.myVote + 1);
			buf.writeVarInt(s.seedSaves);
			buf.writeBoolean(s.mySeedSave);
			buf.writeUtf(s.seed);
			buf.writeVarInt(s.livesUsed);
		}, buf -> new PostStatus(buf.readVarInt(), buf.readVarInt(), new int[]{buf.readVarInt(), buf.readVarInt(), buf.readVarInt()},
				buf.readVarInt() - 1, buf.readVarInt(), buf.readBoolean(), buf.readUtf(), buf.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client to server: my vote for what happens next (voting for the same option again takes it back). */
	public record Vote(int option) implements CustomPacketPayload {
		public static final Type<Vote> ID = id("vote");
		public static final StreamCodec<RegistryFriendlyByteBuf, Vote> CODEC = StreamCodec.ofMember((v, buf) -> buf.writeVarInt(v.option),
				buf -> new Vote(buf.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client to server: add/remove my vote to keep this run's seed in the favourites list. */
	public record SaveSeed() implements CustomPacketPayload {
		public static final Type<SaveSeed> ID = id("save_seed");
		public static final StreamCodec<RegistryFriendlyByteBuf, SaveSeed> CODEC = StreamCodec.unit(new SaveSeed());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Server to client: the post-game ended without a restart (Another life), so close the summary. */
	public record PostGameEnd() implements CustomPacketPayload {
		public static final Type<PostGameEnd> ID = id("post_game_end");
		public static final StreamCodec<RegistryFriendlyByteBuf, PostGameEnd> CODEC = StreamCodec.unit(new PostGameEnd());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** One server tick of a player's movement, recorded for the kill cam replay. */
	public record Frame(double x, double y, double z, float yaw, float pitch, float headYaw, byte flags) {
		public static final byte CROUCH = 1;
		public static final byte SWIM = 2;
		public static final byte SWING = 4;
		public static final byte HURT = 8;
		public static final byte GLIDE = 16;

		public boolean has(byte flag) {
			return (flags & flag) != 0;
		}
	}

	/**
	 * Server to client: replay the last few seconds of a player's life, ending in their death.
	 * The server's orbit camera and the client's replay both follow {@link #frameAt}, so they stay in step.
	 */
	public record Killcam(java.util.UUID player, String name, String dimension, List<Frame> frames,
						  List<net.minecraft.world.item.ItemStack> gear) implements CustomPacketPayload {
		public static final Type<Killcam> ID = id("killcam");
		/** The last second plays at half speed. */
		static final int SLOW_FRAMES = 20;
		public static final StreamCodec<RegistryFriendlyByteBuf, Killcam> CODEC = StreamCodec.ofMember((k, buf) -> {
			buf.writeUUID(k.player);
			buf.writeUtf(k.name);
			buf.writeUtf(k.dimension);
			buf.writeVarInt(k.frames.size());
			for (Frame f : k.frames) {
				buf.writeDouble(f.x());
				buf.writeDouble(f.y());
				buf.writeDouble(f.z());
				buf.writeFloat(f.yaw());
				buf.writeFloat(f.pitch());
				buf.writeFloat(f.headYaw());
				buf.writeByte(f.flags());
			}
			buf.writeVarInt(k.gear.size());
			for (net.minecraft.world.item.ItemStack s : k.gear) {
				net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, s);
			}
		}, buf -> {
			java.util.UUID uuid = buf.readUUID();
			String name = buf.readUtf();
			String dim = buf.readUtf();
			int n = Math.min(buf.readVarInt(), 400);
			List<Frame> frames = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				frames.add(new Frame(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat(),
						buf.readFloat(), buf.readByte()));
			}
			int g = Math.min(buf.readVarInt(), 16);
			List<net.minecraft.world.item.ItemStack> gear = new ArrayList<>(g);
			for (int i = 0; i < g; i++) {
				gear.add(net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
			}
			return new Killcam(uuid, name, dim, frames, gear);
		});

		/** Which (fractional) frame to show `tick` ticks into the replay. */
		public static double frameAt(int tick, int frameCount) {
			if (frameCount <= 1) {
				return 0;
			}
			int slowStart = Math.max(0, frameCount - SLOW_FRAMES);
			double idx = tick < slowStart ? tick : slowStart + (tick - slowStart) / 2.0;
			return Math.min(frameCount - 1, idx);
		}

		/** Ticks until the replay reaches the moment of death. */
		public static int replayTicks(int frameCount) {
			if (frameCount <= 1) {
				return 0;
			}
			int slowStart = Math.max(0, frameCount - SLOW_FRAMES);
			return slowStart + 2 * (frameCount - 1 - slowStart);
		}

		/** Position/rotation between two frames. */
		public static Frame lerp(List<Frame> frames, double idx) {
			int i = (int) Math.floor(idx);
			Frame a = frames.get(Math.max(0, Math.min(frames.size() - 1, i)));
			Frame b = frames.get(Math.max(0, Math.min(frames.size() - 1, i + 1)));
			float t = (float) (idx - i);
			return new Frame(a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t, a.z() + (b.z() - a.z()) * t,
					a.yaw() + net.minecraft.util.Mth.wrapDegrees(b.yaw() - a.yaw()) * t,
					a.pitch() + (b.pitch() - a.pitch()) * t,
					a.headYaw() + net.minecraft.util.Mth.wrapDegrees(b.headYaw() - a.headYaw()) * t, a.flags());
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Server to client: you just went down; here's how it happened (your own short replay, your camera only). */
	public record DownCam(Killcam clip) implements CustomPacketPayload {
		public static final Type<DownCam> ID = id("down_cam");
		public static final StreamCodec<RegistryFriendlyByteBuf, DownCam> CODEC = StreamCodec.ofMember(
				(d, buf) -> Killcam.CODEC.encode(buf, d.clip), buf -> new DownCam(Killcam.CODEC.decode(buf)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	public static void register() {
		var s2c = PayloadTypeRegistry.playS2C();
		var c2s = PayloadTypeRegistry.playC2S();
		s2c.register(Killcam.ID, Killcam.CODEC);
		s2c.register(DownCam.ID, DownCam.CODEC);
		s2c.register(State.ID, State.CODEC);
		s2c.register(Open.ID, Open.CODEC);
		s2c.register(Field.ID, Field.CODEC);
		s2c.register(PostGame.ID, PostGame.CODEC);
		s2c.register(PostStatus.ID, PostStatus.CODEC);
		s2c.register(PostGameEnd.ID, PostGameEnd.CODEC);
		c2s.register(Update.ID, Update.CODEC);
		c2s.register(Vote.ID, Vote.CODEC);
		c2s.register(SaveSeed.ID, SaveSeed.CODEC);
		ShopPayloads.register(s2c, c2s);
		PregamePayloads.register(s2c, c2s);
	}
}
