package moonahmc.bubble.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Same shape as Fabric's PayloadTypeRegistry so the payload classes stay identical between the two versions. Payload
 * types are collected here and handed to NeoForge in {@link Networking#register}.
 */
public final class PayloadTypeRegistry<B> {
	record Entry<T extends CustomPacketPayload>(CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
	}

	private static final PayloadTypeRegistry<RegistryFriendlyByteBuf> S2C = new PayloadTypeRegistry<>();
	private static final PayloadTypeRegistry<RegistryFriendlyByteBuf> C2S = new PayloadTypeRegistry<>();

	final List<Entry<?>> entries = new ArrayList<>();

	private PayloadTypeRegistry() {
	}

	public static PayloadTypeRegistry<RegistryFriendlyByteBuf> playS2C() {
		return S2C;
	}

	public static PayloadTypeRegistry<RegistryFriendlyByteBuf> playC2S() {
		return C2S;
	}

	@SuppressWarnings("unchecked")
	public <T extends CustomPacketPayload> void register(CustomPacketPayload.Type<T> type, StreamCodec<? super B, T> codec) {
		entries.add(new Entry<>(type, (StreamCodec<? super RegistryFriendlyByteBuf, T>) (StreamCodec<?, T>) codec));
	}

	static List<Entry<?>> s2c() {
		return S2C.entries;
	}

	static List<Entry<?>> c2s() {
		return C2S.entries;
	}
}
