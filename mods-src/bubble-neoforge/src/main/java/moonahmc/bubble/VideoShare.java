package moonahmc.bubble;

import moonahmc.bubble.net.PayloadTypeRegistry;
import moonahmc.bubble.net.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The shop's Video tab: pay Strange Matter, paste a link (YouTube, Twitch, a direct video...), and it plays in a window
 * on everyone's screen (drawn by the client with WaterMedia, the library WaterFrames uses). One video at a time; a new
 * one can start 20 seconds after the last. Players who join mid-video pick it up where it is. Ops: /bubble video stop.
 */
public final class VideoShare {
	public static final int MAX_URL = 400;
	private static final long COOLDOWN_MS = 20_000;
	/** Nobody hears about a video more than this long after it started (the client also stops it when it ends). */
	private static final long MAX_MS = 15 * 60_000;

	/** Client to server: show this link to everyone (charges the Video tab's price). */
	public record Request(String url) implements CustomPacketPayload {
		public static final Type<Request> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "video_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.ofMember((r, buf) -> buf.writeUtf(r.url, MAX_URL),
				buf -> new Request(buf.readUtf(MAX_URL)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Server to client: play `url` (started `elapsedMs` ago, by `by`); an empty url stops it. */
	public record Start(String url, String by, long elapsedMs) implements CustomPacketPayload {
		public static final Type<Start> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "video_start"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Start> CODEC = StreamCodec.ofMember((s, buf) -> {
			buf.writeUtf(s.url, MAX_URL);
			buf.writeUtf(s.by, 64);
			buf.writeVarLong(s.elapsedMs);
		}, buf -> new Start(buf.readUtf(MAX_URL), buf.readUtf(64), buf.readVarLong()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	private static MinecraftServer server;
	private static String url = "";
	private static String by = "";
	private static long startedAt;

	private VideoShare() {
	}

	static void register(PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c, PayloadTypeRegistry<RegistryFriendlyByteBuf> c2s) {
		s2c.register(Start.ID, Start.CODEC);
		c2s.register(Request.ID, Request.CODEC);
	}

	static void init() {
		ServerPlayNetworking.registerGlobalReceiver(Request.ID, (payload, context) -> context.server().execute(() -> request(context.player(), payload.url())));
		var bus = NeoForge.EVENT_BUS;
		bus.addListener((ServerStartedEvent e) -> server = e.getServer());
		bus.addListener((ServerStoppedEvent e) -> {
			server = null;
			url = "";
		});
		bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
			if (e.getEntity() instanceof ServerPlayer p && playing()) {
				send(p, new Start(url, by, System.currentTimeMillis() - startedAt));
			}
		});
	}

	private static boolean playing() {
		return !url.isEmpty() && System.currentTimeMillis() - startedAt < MAX_MS;
	}

	public static boolean validUrl(String u) {
		return u.length() <= MAX_URL && u.matches("(?i)https?://[^\\s]+");
	}

	private static void send(ServerPlayer p, Start s) {
		if (ServerPlayNetworking.canSend(p, Start.ID)) {
			ServerPlayNetworking.send(p, s);
		}
	}

	private static void fail(ServerPlayer p, String why) {
		p.displayClientMessage(Component.literal(why).withStyle(ChatFormatting.RED), true);
		p.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.6f, 1.2f);
	}

	static void request(ServerPlayer p, String link) {
		link = link.trim();
		int price = Shop.videoPrice();
		if (server == null || price < 0) {
			fail(p, "Videos aren't available on this server.");
			return;
		}
		if (Shop.closed) {
			fail(p, "The shop is closed.");
			return;
		}
		if (!validUrl(link)) {
			fail(p, "That isn't a link (it should start with https://).");
			return;
		}
		long since = System.currentTimeMillis() - startedAt;
		if (playing() && since < COOLDOWN_MS) {
			fail(p, "Another video just started. Try again in " + ((COOLDOWN_MS - since) / 1000 + 1) + "s.");
			return;
		}
		if (!Matter.add(p, -price)) {
			fail(p, "Not enough Strange Matter (" + Matter.format(Matter.balance(p)) + " / " + price + ")");
			return;
		}
		url = link;
		by = p.getGameProfile().getName();
		startedAt = System.currentTimeMillis();
		Start start = new Start(url, by, 0);
		final String shown = link;
		server.getPlayerList().broadcastSystemMessage(Component.literal("📺 " + by + " is showing everyone a video: ").withStyle(ChatFormatting.AQUA)
				.append(Component.literal(shown.length() > 60 ? shown.substring(0, 57) + "..." : shown)
						.withStyle(s -> s.withColor(ChatFormatting.GRAY).withUnderlined(true).withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, shown))))
				.append(Component.literal(" (F7 hides it)").withStyle(ChatFormatting.DARK_GRAY)), false);
		server.getPlayerList().getPlayers().forEach(q -> send(q, start));
		Bubble.LOG.info("{} paid {} Strange Matter to show a video: {}", by, price, link);
	}

	/** /bubble video stop */
	static boolean stop(String who) {
		if (server == null || !playing()) {
			return false;
		}
		url = "";
		server.getPlayerList().getPlayers().forEach(q -> send(q, new Start("", "", 0)));
		server.getPlayerList().broadcastSystemMessage(Component.literal("📺 " + who + " stopped the video.").withStyle(ChatFormatting.GRAY), false);
		return true;
	}
}
