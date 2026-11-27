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
final class ShopWindows {
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
		return ways.isEmpty() ? "" : "Opens for a while after " + String.join(", ", ways) + ".";
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

	private static int openCmd(CommandSourceStack src, java.util.Collection<ServerPlayer> players, int secs) {
		for (ServerPlayer p : players) {
			open(p, secs, "opened by " + src.getTextName());
		}
		src.sendSuccess(() -> Component.literal("Opened the shop for " + players.size() + " player(s), " + secs + "s"), true);
		return players.size();
	}
}
