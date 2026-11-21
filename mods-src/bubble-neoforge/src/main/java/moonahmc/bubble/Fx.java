package moonahmc.bubble;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Timed effects the client has to do itself (from mid-run wheel slices): "upside_down" flips the camera, "ice" makes the
 * ground slide like blue ice (player movement is worked out on the client, so friction has to be changed there).
 */
public final class Fx {
	/** Server to client: turn `kind` on for `ticks` (0 turns it off). */
	public record Start(String kind, int ticks) implements CustomPacketPayload {
		public static final Type<Start> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "fx"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Start> CODEC = StreamCodec.ofMember((s, buf) -> {
			buf.writeUtf(s.kind, 32);
			buf.writeVarInt(s.ticks);
		}, buf -> new Start(buf.readUtf(32), buf.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client clock (System millis) until which the local player slides like on blue ice. Read by LivingEntityMixin. */
	public static volatile long iceUntil;
	public static final float ICE = 0.989f;

	private Fx() {
	}

	public static boolean iceOn() {
		return System.currentTimeMillis() < iceUntil;
	}
}
