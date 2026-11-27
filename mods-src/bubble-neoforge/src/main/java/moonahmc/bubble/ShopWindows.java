package moonahmc.bubble;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The shop (and the Gamble tab, and charging) only opens for a short while at key moments of a run, so you can't sell a
 * stack of blocks and gamble straight away whenever you like:
 * <ul>
 * <li>the start of the run (everyone),</li>
 * <li>the first time anyone goes into a new dimension (everyone),</li>
 * <li>a Cataclysm boss going down (everyone who helped),</li>
 * <li>killing an elite or a mini-boss (that player),</li>
 * <li>every 10 Lootr chests you open (that player).</li>
 * </ul>
 * Selling always works. Outside a run (lobby, post-game) the shop is open. Off with /bubble shopwindow off; the times
 * are in config/bubble.json (shop...Seconds). State (dimensions seen, chest counts) is kept in the world folder, so a
 * new world starts fresh.
 */
public final class ShopWindows {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static MinecraftServer server;
	private static Path file;
	/** Server tick the everyone-window ends at. */
	private static int allUntil;
	private static String allWhy = "";
	/** Per-player window ends (server tick) and why it opened. */
	private static final Map<UUID, Integer> until = new HashMap<>();
	private static final Map<UUID, String> why = new HashMap<>();
	/** What each player saw last second (to tell them when it opens or closes). */
	private static final Map<UUID, Boolean> wasOpen = new HashMap<>();
	// saved
	private static boolean startPending = true;
	private static final Set<String> dims = new LinkedHashSet<>();
	private static final Map<UUID, Set<String>> chests = new HashMap<>();

	private ShopWindows() {
	}

	static void init() {
		moonahmc.bubble.net.ServerPlayNetworking.registerGlobalReceiver(Edit.ID,
				(payload, context) -> context.server().execute(() -> edit(context.player(), payload)));
		var bus = NeoForge.EVENT_BUS;
		bus.addListener((ServerStartedEvent e) -> {
			server = e.getServer();
			file = server.getWorldPath(LevelResource.ROOT).resolve("bubble-shop-windows.json");
			allUntil = 0;
			until.clear();
			why.clear();
			wasOpen.clear();
			load();
		});
		bus.addListener((ServerStoppedEvent e) -> server = null);
		bus.addListener((ServerTickEvent.Post e) -> {
			if (server != null && server.getTickCount() % 20 == 0) {
				tick();
			}
		});
		bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
			if (e.getEntity() instanceof ServerPlayer p && server != null) {
				server.execute(() -> {
					if (startPending && running()) {
						runStarted(); // no lobby on this world: the run starts when the first player is in
					}
				});
			}
		});
		bus.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
			if (!(e.getEntity() instanceof ServerPlayer p) || !running() || !BubbleServer.counts(p)) {
				return;
			}
			ResourceLocation to = e.getTo().location();
			if (e.getTo() != Level.OVERWORLD && dims.add(to.toString())) {
				save();
				int secs = BubbleServer.config.shopDimensionSeconds;
				if (secs > 0) {
					openAll(secs, p.getGameProfile().getName() + " made it to " + dimName(to));
				}
			}
		});
		// Lootr chests: each one you open (first time) counts towards the next window
		bus.addListener(EventPriority.LOWEST, (PlayerInteractEvent.RightClickBlock e) -> {
			if (e.getLevel().isClientSide || !(e.getEntity() instanceof ServerPlayer p) || !running() || !BubbleServer.counts(p)) {
				return;
			}
			ResourceLocation id = BuiltInRegistries.BLOCK.getKey(e.getLevel().getBlockState(e.getPos()).getBlock());
			if (!id.getNamespace().equals("lootr") && !id.getPath().contains("lootr")) {
				return;
			}
			String where = e.getLevel().dimension().location() + "|" + e.getPos().toShortString();
			Set<String> mine = chests.computeIfAbsent(p.getUUID(), k -> new HashSet<>());
			if (!mine.add(where)) {
				return;
			}
			save();
			int need = Math.max(1, BubbleServer.config.shopChests);
			if (BubbleServer.config.shopChestSeconds > 0 && mine.size() % need == 0) {
				open(p, BubbleServer.config.shopChestSeconds, need + " looter chests");
			} else if (BubbleServer.config.shopWindows) {
				p.displayClientMessage(Component.literal("Looter chests: " + (mine.size() % need) + "/" + need + " to open the shop")
						.withStyle(ChatFormatting.GRAY), true);
			}
		});
		// elites and mini-bosses (Cataclysm bosses are Bosses.defeated)
		bus.addListener(EventPriority.LOW, (LivingDeathEvent e) -> {
			if (!(e.getEntity() instanceof Mob mob) || !(e.getSource().getEntity() instanceof ServerPlayer p) || !running()
					|| p instanceof net.neoforged.neoforge.common.util.FakePlayer || Bosses.handles(mob)) {
				return;
			}
			String type = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
			if ((ApothElites.isElite(mob) || Bosses.MINIBOSSES.contains(type)) && BubbleServer.config.shopEliteSeconds > 0) {
				open(p, BubbleServer.config.shopEliteSeconds, "killed " + mob.getName().getString());
			}
		});
	}

	private static String dimName(ResourceLocation id) {
		return switch (id.toString()) {
			case "minecraft:the_nether" -> "the Nether";
			case "minecraft:the_end" -> "the End";
			default -> {
				String path = id.getPath().replace('_', ' ');
				yield "the " + Character.toUpperCase(path.charAt(0)) + path.substring(1);
			}
		};
	}

	/** In a run, where windows apply (not the lobby, not the post-game). */
	private static boolean running() {
		return server != null && BubbleServer.playing() && !Lobby.active();
	}

	private static int now() {
		return server == null ? 0 : server.getTickCount();
	}

	/** Is this tab (by name) buyable between windows too? */
	static boolean alwaysOpen(String tab) {
		return BubbleServer.config.shopAlwaysOpen.contains(tab);
	}

	// ------------------------------------------------------------------ the admin screen (Bubble menu > Admin > Shop windows)

	/** Server to client: the shop windows settings and every tab with whether it stays open. */
	public record Settings(boolean enabled, int start, int dimension, int boss, int elite, int chest, int chests,
						   List<String> tabs, List<Boolean> open, int goal) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
		public static final Type<Settings> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "shop_windows"));
		public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, Settings> CODEC =
				net.minecraft.network.codec.StreamCodec.ofMember((s, buf) -> {
					buf.writeBoolean(s.enabled);
					for (int v : new int[] {s.start, s.dimension, s.boss, s.elite, s.chest, s.chests}) {
						buf.writeVarInt(v);
					}
					buf.writeVarInt(s.tabs.size());
					for (int i = 0; i < s.tabs.size(); i++) {
						buf.writeUtf(s.tabs.get(i), 64);
						buf.writeBoolean(s.open.get(i));
					}
					buf.writeVarInt(s.goal);
				}, buf -> {
					boolean on = buf.readBoolean();
					int[] v = new int[6];
					for (int i = 0; i < 6; i++) {
						v[i] = buf.readVarInt();
					}
					int n = Math.min(buf.readVarInt(), 128);
					List<String> tabs = new ArrayList<>(n);
					List<Boolean> open = new ArrayList<>(n);
					for (int i = 0; i < n; i++) {
						tabs.add(buf.readUtf(64));
						open.add(buf.readBoolean());
					}
					return new Settings(on, v[0], v[1], v[2], v[3], v[4], v[5], tabs, open, buf.readVarInt());
				});

		@Override
		public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client to server: an op changed them (or, with request set, just wants them). */
	public record Edit(boolean request, Settings settings) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
		public static final Type<Edit> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "shop_windows_edit"));
		public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, Edit> CODEC =
				net.minecraft.network.codec.StreamCodec.ofMember((e, buf) -> {
					buf.writeBoolean(e.request);
					Settings.CODEC.encode(buf, e.settings);
				}, buf -> new Edit(buf.readBoolean(), Settings.CODEC.decode(buf)));

		@Override
		public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
			return ID;
		}
	}

	static void register(moonahmc.bubble.net.PayloadTypeRegistry<net.minecraft.network.RegistryFriendlyByteBuf> s2c,
						 moonahmc.bubble.net.PayloadTypeRegistry<net.minecraft.network.RegistryFriendlyByteBuf> c2s) {
		s2c.register(Settings.ID, Settings.CODEC);
		c2s.register(Edit.ID, Edit.CODEC);
	}

	static Settings settings() {
		BubbleConfig c = BubbleServer.config;
		List<String> tabs = Shop.tabNames();
		List<Boolean> open = tabs.stream().map(ShopWindows::alwaysOpen).toList();
		return new Settings(c.shopWindows, c.shopStartSeconds, c.shopDimensionSeconds, c.shopBossSeconds, c.shopEliteSeconds,
				c.shopChestSeconds, c.shopChests, tabs, open, c.shopGoalSeconds);
	}

	private static void sendSettings(ServerPlayer p) {
		if (moonahmc.bubble.net.ServerPlayNetworking.canSend(p, Settings.ID)) {
			moonahmc.bubble.net.ServerPlayNetworking.send(p, settings());
		}
	}

	private static void edit(ServerPlayer p, Edit e) {
		if (!p.hasPermissions(2)) {
			return;
		}
		if (!e.request()) {
			Settings s = e.settings();
			BubbleConfig c = BubbleServer.config;
			boolean wasOn = c.shopWindows;
			c.shopWindows = s.enabled();
			c.shopStartSeconds = s.start();
			c.shopDimensionSeconds = s.dimension();
			c.shopBossSeconds = s.boss();
			c.shopEliteSeconds = s.elite();
			c.shopChestSeconds = s.chest();
			c.shopChests = s.chests();
			c.shopGoalSeconds = s.goal();
			// tabs this screen knows about are set from it; any others in the list (hidden tabs) are kept
			List<String> known = Shop.tabNames();
			List<String> keep = new ArrayList<>(c.shopAlwaysOpen.stream().filter(t -> !known.contains(t)).toList());
			for (int i = 0; i < s.tabs().size() && i < s.open().size(); i++) {
				if (s.open().get(i) && known.contains(s.tabs().get(i))) {
					keep.add(s.tabs().get(i));
				}
			}
			c.shopAlwaysOpen = keep;
			c.clamp();
			BubbleServer.saveConfig();
			wasOpen.clear();
			if (server != null) {
				server.getPlayerList().getPlayers().forEach(Shop::sendCatalog);
				if (wasOn != c.shopWindows) {
					server.getPlayerList().broadcastSystemMessage(Component.literal(c.shopWindows
							? "🛒 " + p.getGameProfile().getName() + " made the shop open only at key moments of the run."
							: "🛒 " + p.getGameProfile().getName() + " made the shop open all the time.").withStyle(ChatFormatting.AQUA), false);
				}
				server.getPlayerList().getPlayers().stream().filter(q -> q.hasPermissions(2)).forEach(ShopWindows::sendSettings);
			}
			return;
		}
		sendSettings(p);
	}

	/** Can `p` buy right now? */
	static boolean isOpen(ServerPlayer p) {
		return !BubbleServer.config.shopWindows || !running() || left(p) > 0;
	}

	/** Ticks left on `p`'s window (0 = closed). */
	static int left(ServerPlayer p) {
		return Math.max(0, Math.max(allUntil, until.getOrDefault(p.getUUID(), 0)) - now());
	}

	/** For the shop screen: -1 = always open (no windows right now), 0 = closed, otherwise ticks left. */
	static int windowFor(ServerPlayer p) {
		if (!BubbleServer.config.shopWindows || !running()) {
			return -1;
		}
		return left(p);
	}

	/** What opens it, and how close this player is (shown on the closed shop). */
	static String info(ServerPlayer p) {
		BubbleConfig c = BubbleServer.config;
		List<String> ways = new ArrayList<>();
		if (c.shopDimensionSeconds > 0) {
			ways.add("someone reaching a new dimension");
		}
		if (c.shopGoalSeconds > 0) {
			ways.add("a stage of the goals being cleared");
		}
		if (c.shopBossSeconds > 0) {
			ways.add("a boss going down");
		}
		if (c.shopEliteSeconds > 0) {
			ways.add("killing an elite or mini-boss");
		}
		if (c.shopChestSeconds > 0) {
			int need = Math.max(1, c.shopChests);
			int have = chests.getOrDefault(p.getUUID(), Set.of()).size() % need;
			ways.add("every " + need + " looter chests (you: " + have + "/" + need + ")");
		}
		return (ways.isEmpty() ? "" : "Opens for a while after " + String.join(", ", ways) + ". ") + "A Shop Key opens it just for you.";
	}

	/** A Shop Key was used: null if it opened the shop (use one up), otherwise why not (keep it). */
	static String useKey(ServerPlayer p) {
		if (!BubbleServer.config.shopWindows || !running()) {
			return "The shop is open anyway right now.";
		}
		if (left(p) > 0) {
			return "The shop is already open for you.";
		}
		open(p, BubbleServer.config.shopKeySeconds, "Shop Key");
		return null;
	}

	static void refuse(ServerPlayer p) {
		p.displayClientMessage(Component.literal("The shop is closed right now. Selling still works.").withStyle(ChatFormatting.RED), true);
		p.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.6f, 1.2f);
	}

	// ------------------------------------------------------------------ opening

	/** Lobby.start (or the first join on a world without a lobby). */
	static void runStarted() {
		if (!startPending) {
			return;
		}
		startPending = false;
		save();
		int secs = BubbleServer.config.shopStartSeconds;
		if (secs > 0) {
			openAll(secs, "the run started");
		}
	}

	/** Bosses: these players took a boss down. */
	static void bossDown(Iterable<ServerPlayer> helpers, String boss) {
		int secs = BubbleServer.config.shopBossSeconds;
		if (secs > 0 && running()) {
			for (ServerPlayer p : helpers) {
				open(p, secs, boss + " went down");
			}
		}
	}

	static void openAll(int seconds, String reason) {
		if (!BubbleServer.config.shopWindows || server == null) {
			return;
		}
		allUntil = Math.max(allUntil, now() + seconds * 20);
		allWhy = reason;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			announce(p, reason);
		}
	}

	static void open(ServerPlayer p, int seconds, String reason) {
		if (!BubbleServer.config.shopWindows || server == null) {
			return;
		}
		until.merge(p.getUUID(), now() + seconds * 20, Math::max);
		why.put(p.getUUID(), reason);
		announce(p, reason);
	}

	private static void announce(ServerPlayer p, String reason) {
		p.sendSystemMessage(Component.literal("🛒 The shop is open for " + Revive.clock(left(p)) + " (" + reason + ")")
				.withStyle(ChatFormatting.AQUA));
		p.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.8f, 1.4f);
		wasOpen.put(p.getUUID(), true);
		Shop.sendCatalog(p);
	}

	private static void tick() {
		if (!BubbleServer.config.shopWindows || !running()) {
			if (!wasOpen.isEmpty()) {
				wasOpen.clear();
				server.getPlayerList().getPlayers().forEach(Shop::sendCatalog);
			}
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			int left = left(p);
			boolean open = left > 0;
			Boolean before = wasOpen.put(p.getUUID(), open);
			if (before == null) {
				Shop.sendCatalog(p); // windows just started applying: the screen needs to know
			} else if (before && !open) {
				p.sendSystemMessage(Component.literal("🛒 The shop has closed.").withStyle(ChatFormatting.GRAY));
				Shop.sendCatalog(p);
			} else if (open && left > 180 && left <= 200) {
				p.displayClientMessage(Component.literal("The shop closes in 10 seconds").withStyle(ChatFormatting.YELLOW), true);
			}
		}
	}

	// ------------------------------------------------------------------ saving

	private static void load() {
		startPending = true;
		dims.clear();
		chests.clear();
		if (file == null || !Files.exists(file)) {
			return;
		}
		try {
			JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			startPending = !o.has("startPending") || o.get("startPending").getAsBoolean();
			if (o.has("dimensions")) {
				o.getAsJsonArray("dimensions").forEach(d -> dims.add(d.getAsString()));
			}
			if (o.has("chests")) {
				for (var e : o.getAsJsonObject("chests").entrySet()) {
					Set<String> s = new HashSet<>();
					e.getValue().getAsJsonArray().forEach(x -> s.add(x.getAsString()));
					chests.put(UUID.fromString(e.getKey()), s);
				}
			}
		} catch (Exception ex) {
			Bubble.LOG.warn("Could not read {}", file, ex);
		}
	}

	private static void save() {
		if (file == null) {
			return;
		}
		JsonObject o = new JsonObject();
		o.addProperty("startPending", startPending);
		JsonArray d = new JsonArray();
		dims.forEach(d::add);
		o.add("dimensions", d);
		JsonObject c = new JsonObject();
		chests.forEach((id, s) -> {
			JsonArray a = new JsonArray();
			s.forEach(a::add);
			c.add(id.toString(), a);
		});
		o.add("chests", c);
		try {
			Files.writeString(file, GSON.toJson(o));
		} catch (Exception ex) {
			Bubble.LOG.warn("Could not save {}", file, ex);
		}
	}

	// ------------------------------------------------------------------ command

	/** /bubble shopwindow [on|off|open <players> [seconds]] */
	static LiteralArgumentBuilder<CommandSourceStack> command() {
		return Commands.literal("shopwindow").requires(s -> s.hasPermission(2))
				.executes(c -> {
					c.getSource().sendSuccess(() -> Component.literal("Shop windows are " + (BubbleServer.config.shopWindows ? "on" : "off (the shop is always open)")
							+ ". /bubble shopwindow on|off|open <players> [seconds]"), false);
					return 1;
				})
				.then(Commands.literal("tab").then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.string())
						.then(Commands.literal("always").executes(c -> tab(c.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "name"), true)))
						.then(Commands.literal("windows").executes(c -> tab(c.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "name"), false)))))
				.then(Commands.literal("on").executes(c -> set(c.getSource(), true)))
				.then(Commands.literal("off").executes(c -> set(c.getSource(), false)))
				.then(Commands.literal("open").then(Commands.argument("players", EntityArgument.players())
						.executes(c -> openCmd(c.getSource(), EntityArgument.getPlayers(c, "players"), 60))
						.then(Commands.argument("seconds", IntegerArgumentType.integer(1, 3600))
								.executes(c -> openCmd(c.getSource(), EntityArgument.getPlayers(c, "players"), IntegerArgumentType.getInteger(c, "seconds"))))));
	}

	private static int set(CommandSourceStack src, boolean on) {
		BubbleServer.config.shopWindows = on;
		BubbleServer.saveConfig();
		if (server != null) {
			wasOpen.clear();
			server.getPlayerList().getPlayers().forEach(Shop::sendCatalog);
			server.getPlayerList().broadcastSystemMessage(Component.literal(on
					? "🛒 The shop now only opens at key moments of the run."
					: "🛒 The shop is open all the time again.").withStyle(ChatFormatting.AQUA), false);
		}
		src.sendSuccess(() -> Component.literal("Shop windows " + (on ? "on" : "off")), true);
		return 1;
	}

	private static int tab(CommandSourceStack src, String name, boolean always) {
		List<String> list = BubbleServer.config.shopAlwaysOpen;
		list.remove(name);
		if (always) {
			list.add(name);
		}
		BubbleServer.saveConfig();
		if (server != null) {
			server.getPlayerList().getPlayers().forEach(Shop::sendCatalog);
		}
		src.sendSuccess(() -> Component.literal("Shop tab '" + name + "' " + (always ? "stays open between windows" : "follows the shop windows")
				+ (Shop.tabNames().contains(name) ? "" : " (no tab has that name right now)")), true);
		return 1;
	}

	private static int openCmd(CommandSourceStack src, java.util.Collection<ServerPlayer> players, int secs) {
		for (ServerPlayer p : players) {
			open(p, secs, "opened by " + src.getTextName());
		}
		src.sendSuccess(() -> Component.literal("Opened the shop for " + players.size() + " player(s), " + secs + "s"), true);
		return players.size();
	}
}
