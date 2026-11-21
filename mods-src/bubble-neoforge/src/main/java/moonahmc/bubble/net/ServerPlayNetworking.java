package moonahmc.bubble.net;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Same calls as Fabric's ServerPlayNetworking, backed by NeoForge's networking. */
public final class ServerPlayNetworking {
	private ServerPlayNetworking() {
	}

	public interface PlayPayloadHandler<T extends CustomPacketPayload> {
		void receive(T payload, Context context);
	}

	public record Context(MinecraftServer server, ServerPlayer player) {
	}

	public static <T extends CustomPacketPayload> void registerGlobalReceiver(CustomPacketPayload.Type<T> type, PlayPayloadHandler<T> handler) {
		Networking.SERVER.put(type, handler);
	}

	/** True when the player's client has Bubble (vanilla players and other clients don't). */
	public static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
		return player.connection != null && player.connection.hasChannel(type);
	}

	public static void send(ServerPlayer player, CustomPacketPayload payload) {
		PacketDistributor.sendToPlayer(player, payload);
	}
}
