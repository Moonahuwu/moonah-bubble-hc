package moonahmc.bubble;

import moonahmc.bubble.net.PayloadTypeRegistry;
import moonahmc.bubble.net.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** A small pop-up in the top-right corner (over any screen), e.g. "✔ Run started" after an admin button. */
public record Notice(String title, String body, boolean ok) implements CustomPacketPayload {
	public static final Type<Notice> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "notice"));
	public static final StreamCodec<RegistryFriendlyByteBuf, Notice> CODEC = StreamCodec.ofMember((n, buf) -> {
		buf.writeUtf(n.title, 128);
		buf.writeUtf(n.body, 256);
		buf.writeBoolean(n.ok);
	}, buf -> new Notice(buf.readUtf(128), buf.readUtf(256), buf.readBoolean()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}

	static void register(PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c) {
		s2c.register(ID, CODEC);
	}

	/** Shows it to that player (players without the mod get it on the action bar instead). */
	static void send(ServerPlayer p, boolean ok, String title, String body) {
		if (p == null) {
			return;
		}
		String t = title.length() > 120 ? title.substring(0, 120) : title;
		String b = body == null ? "" : body.length() > 250 ? body.substring(0, 250) : body;
		if (ServerPlayNetworking.canSend(p, ID)) {
			ServerPlayNetworking.send(p, new Notice(t, b, ok));
		} else {
			p.displayClientMessage(net.minecraft.network.chat.Component.literal(t + (b.isEmpty() ? "" : ": " + b))
					.withStyle(ok ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED), true);
		}
	}
}
