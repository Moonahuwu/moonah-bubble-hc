package moonahmc.bubble.net;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Registers every payload with NeoForge. The channel is optional, so players without the mod (or a server without it)
 * can still connect. Handlers run on the main thread and are looked up when a packet arrives, so receivers can be
 * added at any time (the client adds its own from the client entrypoint).
 */
public final class Networking {
	static final Map<CustomPacketPayload.Type<?>, ServerPlayNetworking.PlayPayloadHandler<?>> SERVER = new ConcurrentHashMap<>();
	static final Map<CustomPacketPayload.Type<?>, Consumer<?>> CLIENT = new ConcurrentHashMap<>();

	private Networking() {
	}

	public static void register(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar("1").optional();
		for (PayloadTypeRegistry.Entry<?> e : PayloadTypeRegistry.s2c()) {
			toClient(registrar, e);
		}
		for (PayloadTypeRegistry.Entry<?> e : PayloadTypeRegistry.c2s()) {
			toServer(registrar, e);
		}
	}

	/** Used by the client entrypoint. */
	public static <T extends CustomPacketPayload> void onClient(CustomPacketPayload.Type<T> type, Consumer<T> handler) {
		CLIENT.put(type, handler);
	}

	@SuppressWarnings("unchecked")
	private static <T extends CustomPacketPayload> void toClient(PayloadRegistrar registrar, PayloadTypeRegistry.Entry<T> e) {
		registrar.playToClient(e.type(), e.codec(), (payload, context) -> {
			Consumer<T> h = (Consumer<T>) CLIENT.get(e.type());
			if (h != null) {
				h.accept(payload);
			}
		});
	}

	@SuppressWarnings("unchecked")
	private static <T extends CustomPacketPayload> void toServer(PayloadRegistrar registrar, PayloadTypeRegistry.Entry<T> e) {
		registrar.playToServer(e.type(), e.codec(), (payload, context) -> {
			ServerPlayNetworking.PlayPayloadHandler<T> h = (ServerPlayNetworking.PlayPayloadHandler<T>) SERVER.get(e.type());
			if (h != null && context.player() instanceof ServerPlayer player) {
				h.receive(payload, new ServerPlayNetworking.Context(player.server, player));
			}
		});
	}
}
