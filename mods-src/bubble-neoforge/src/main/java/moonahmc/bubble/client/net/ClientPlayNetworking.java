package moonahmc.bubble.client.net;

import moonahmc.bubble.net.Networking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

/** Same calls as Fabric's ClientPlayNetworking, backed by NeoForge's networking. */
public final class ClientPlayNetworking {
	private ClientPlayNetworking() {
	}

	public interface PlayPayloadHandler<T extends CustomPacketPayload> {
		void receive(T payload, Context context);
	}

	public record Context(Minecraft client) {
	}

	public static <T extends CustomPacketPayload> void registerGlobalReceiver(CustomPacketPayload.Type<T> type, PlayPayloadHandler<T> handler) {
		Networking.onClient(type, payload -> handler.receive(payload, new Context(Minecraft.getInstance())));
	}

	public static void send(CustomPacketPayload payload) {
		PacketDistributor.sendToServer(payload);
	}
}
