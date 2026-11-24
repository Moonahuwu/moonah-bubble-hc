package moonahmc.bubble;

import moonahmc.bubble.net.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** The "Next run" tab of the Bubble menu: pre-game settings. */
public final class PregamePayloads {
	private PregamePayloads() {
	}

	/** Structures from one mod (by namespace): how many structure sets it has and how often they should appear. */
	public record Group(String namespace, String name, int count, int level) {
	}

	/** The settings as the client edits them. */
	public record Settings(int lucky, int rainbow, int nuke, int speed, int difficulty, String nextSeed, List<Group> groups, int gatling, int wheel, int lobby, int watchers, int mode, List<String> modes, int jockeys, int swarm) {
		void write(RegistryFriendlyByteBuf buf) {
			buf.writeVarInt(lucky);
			buf.writeVarInt(rainbow);
			buf.writeVarInt(nuke);
			buf.writeVarInt(speed);
			buf.writeVarInt(difficulty);
			buf.writeUtf(nextSeed, 64);
			buf.writeVarInt(groups.size());
			for (Group g : groups) {
				buf.writeUtf(g.namespace, 128);
				buf.writeUtf(g.name, 128);
				buf.writeVarInt(g.count);
				buf.writeVarInt(g.level);
			}
			buf.writeVarInt(gatling);
			buf.writeVarInt(wheel);
			buf.writeVarInt(lobby);
			buf.writeVarInt(watchers);
			buf.writeVarInt(mode);
			buf.writeVarInt(modes.size());
			modes.forEach(m -> buf.writeUtf(m, 64));
			buf.writeVarInt(jockeys);
			buf.writeVarInt(swarm);
		}

		static Settings read(RegistryFriendlyByteBuf buf) {
			int lucky = buf.readVarInt();
			int rainbow = buf.readVarInt();
			int nuke = buf.readVarInt();
			int speed = buf.readVarInt();
			int difficulty = buf.readVarInt();
			String seed = buf.readUtf(64);
			int n = Math.min(buf.readVarInt(), 512);
			List<Group> groups = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				groups.add(new Group(buf.readUtf(128), buf.readUtf(128), buf.readVarInt(), buf.readVarInt()));
			}
			int gatling = buf.readVarInt();
			int wheel = buf.readVarInt();
			int lobby = buf.readVarInt();
			int watchers = buf.readVarInt();
			int mode = buf.readVarInt();
			List<String> modes = readModes(buf);
			return new Settings(lucky, rainbow, nuke, speed, difficulty, seed, groups, gatling, wheel, lobby, watchers, mode, modes, buf.readVarInt(), buf.readVarInt());
		}
	}

	private static List<String> readModes(RegistryFriendlyByteBuf buf) {
		int n = Math.min(buf.readVarInt(), 32);
		List<String> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			out.add(buf.readUtf(64));
		}
		return out;
	}

	/** Server to client: current pre-game settings. */
	public record State(boolean canEdit, Settings settings) implements CustomPacketPayload {
		public static final Type<State> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "pregame_state"));
		public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.ofMember((s, buf) -> {
			buf.writeBoolean(s.canEdit);
			s.settings.write(buf);
		}, buf -> new State(buf.readBoolean(), Settings.read(buf)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client to server (ops): new pre-game settings. */
	public record Update(Settings settings) implements CustomPacketPayload {
		public static final Type<Update> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "pregame_update"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Update> CODEC = StreamCodec.ofMember((u, buf) -> u.settings.write(buf),
				buf -> new Update(Settings.read(buf)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	static void register(PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c, PayloadTypeRegistry<RegistryFriendlyByteBuf> c2s) {
		s2c.register(State.ID, State.CODEC);
		Wheel.register(s2c);
		WheelAdmin.register(s2c, c2s);
		Notice.register(s2c);
		Revive.register(s2c, c2s);
		Gamble.register(s2c, c2s);
		VideoShare.register(s2c, c2s);
		Lobby.register(s2c, c2s);
		Goals.register(s2c, c2s);
		Eidolons.register(s2c, c2s);
		CurrencyAdmin.register(s2c, c2s);
		c2s.register(Update.ID, Update.CODEC);
	}
}
