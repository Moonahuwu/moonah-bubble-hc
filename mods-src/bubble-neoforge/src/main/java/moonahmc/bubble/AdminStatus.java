package moonahmc.bubble;

import moonahmc.bubble.net.PayloadTypeRegistry;
import moonahmc.bubble.net.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * The status card at the top of the Bubble menu's Admin tab: the run, the director, the shop and who's down, a line
 * each. The client asks about once a second while the tab is open.
 */
public final class AdminStatus {
	private AdminStatus() {
	}

	public record Request() implements CustomPacketPayload {
		public static final Type<Request> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "admin_status_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.unit(new Request());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	public record Lines(List<String> lines) implements CustomPacketPayload {
		public static final Type<Lines> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "admin_status"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Lines> CODEC = StreamCodec.ofMember((l, buf) -> {
			buf.writeVarInt(l.lines.size());
			l.lines.forEach(s -> buf.writeUtf(s, 256));
		}, buf -> {
			int n = Math.min(buf.readVarInt(), 16);
			List<String> out = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				out.add(buf.readUtf(256));
			}
			return new Lines(out);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	static void register(PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c, PayloadTypeRegistry<RegistryFriendlyByteBuf> c2s) {
		s2c.register(Lines.ID, Lines.CODEC);
		c2s.register(Request.ID, Request.CODEC);
	}

	static void init() {
		ServerPlayNetworking.registerGlobalReceiver(Request.ID, (payload, context) -> context.server().execute(() -> {
			ServerPlayer p = context.player();
			if (p.hasPermissions(2) && ServerPlayNetworking.canSend(p, Lines.ID)) {
				ServerPlayNetworking.send(p, new Lines(lines(p)));
			}
		}));
	}

	private static String clock(int secs) {
		return secs / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", secs % 60);
	}

	private static List<String> lines(ServerPlayer p) {
		List<String> out = new ArrayList<>();
		int online = p.server.getPlayerList().getPlayerCount();
		String phase = Lobby.active() ? "in the ready-up lobby" : BubbleServer.playing() ? "playing" : "between runs";
		out.add("Run #" + BubbleServer.config.run + "  ·  " + phase + "  ·  " + online + (online == 1 ? " player" : " players") + " online"
				+ "  ·  " + Goals.stagesCleared() + (Goals.stagesCleared() == 1 ? " stage" : " stages") + " cleared");
		if (!RandomEvents.enabled()) {
			out.add("Random events: off");
		} else if (RandomEvents.director()) {
			out.add(Director.status());
		} else {
			int next = RandomEvents.secondsToNext();
			out.add(next < 0 ? "Random events: waiting for the run" : "Random events: next in about " + clock(next));
		}
		out.add(ShopWindows.status(p));
		out.add(Revive.statusLine());
		return out;
	}
}
