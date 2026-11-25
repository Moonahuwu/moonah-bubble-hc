package moonahmc.bubble;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import moonahmc.bubble.net.PayloadTypeRegistry;
import moonahmc.bubble.net.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Eidolons: the currency that survives every reset. You earn them when a run finishes (1 per 100 Strange Matter you
 * hold, 2 per stage cleared, 10 for completing the game mode) and spend them on permanent upgrades and cosmetics, or
 * bet them in the lobby on who ends the run. Also keeps the hall of fame. Saved in bubble-eidolons.json in the server
 * folder (not the world), so it carries over from run to run.
 */
public final class Eidolons {
	// ------------------------------------------------------------------ catalogue (shared with the client)

	public record Upgrade(String id, String name, String desc, int[] costs) {
	}

	public record Cosmetic(String id, String kind, String name, int cost, int color) {
	}

	public static final List<Upgrade> UPGRADES = List.of(
			new Upgrade("tough", "Tough Skin", "+1 heart per level", new int[]{10, 25, 50}),
			new Upgrade("lucky", "Four-Leaf", "+0.5 Luck per level (better case odds)", new int[]{8, 16, 32, 64}),
			new Upgrade("allowance", "Allowance", "+50 Strange Matter at the start of each run", new int[]{5, 10, 15, 20, 30}),
			new Upgrade("haggler", "Haggler", "+5% Strange Matter when selling", new int[]{10, 20, 35, 60}),
			new Upgrade("kit", "Head Start", "Start each run with tools (stone, iron, then armour)", new int[]{10, 30, 80}));

	public static final List<Cosmetic> COSMETICS = List.of(
			new Cosmetic("trail_hearts", "trail", "Heart trail", 15, 0xFF6B8A),
			new Cosmetic("trail_flame", "trail", "Flame trail", 15, 0xFF9A3C),
			new Cosmetic("trail_notes", "trail", "Music trail", 15, 0x6BE07A),
			new Cosmetic("trail_soul", "trail", "Soul fire trail", 20, 0x52C9E8),
			new Cosmetic("trail_end", "trail", "End rod trail", 20, 0xF2EBD2),
			new Cosmetic("trail_cherry", "trail", "Cherry petal trail", 25, 0xF7A8D0),
			new Cosmetic("trail_rainbow", "trail", "Rainbow trail", 60, 0xD36BFF),
			// Ribbon trails (GMod style): a textured ribbon behind you that fades out (client/RibbonTrails)
			new Cosmetic("ribbon_plasma", "ribbon", "Plasma", 30, 0x52D8FF),
			new Cosmetic("ribbon_electric", "ribbon", "Electric", 30, 0xFFF27A),
			new Cosmetic("ribbon_love", "ribbon", "Love", 30, 0xFF6B8A),
			new Cosmetic("ribbon_smoke", "ribbon", "Smoke", 25, 0xB8C0CC),
			new Cosmetic("ribbon_fire", "ribbon", "Fire", 35, 0xFF8A2A),
			new Cosmetic("ribbon_stars", "ribbon", "Stars", 40, 0xFFD34E),
			new Cosmetic("ribbon_bubbles", "ribbon", "Bubbles", 40, 0x8FE3FF),
			new Cosmetic("ribbon_rainbow", "ribbon", "Rainbow", 60, 0xD36BFF),
			new Cosmetic("ribbon_trans", "ribbon", "Trans flag", 60, 0xF5A9B8),
			new Cosmetic("ribbon_lol", "ribbon", "LOL", 75, 0xFFFFFF),
			new Cosmetic("title_gambler", "title", "Gambler", 10, 0xF2C14E),
			new Cosmetic("title_bait", "title", "Watcher Bait", 15, 0xFF4040),
			new Cosmetic("title_survivor", "title", "Nuke Survivor", 20, 0x7CE07C),
			new Cosmetic("title_veteran", "title", "Bubble Veteran", 25, 0x55E0E8),
			new Cosmetic("title_legend", "title", "Legend", 100, 0xFF7CFF),
			// Name colours (NameStyles): your name in chat, the tab list and above your head
			new Cosmetic("name_gold", "name", "Gold", 15, 0xFFAA00),
			new Cosmetic("name_aqua", "name", "Aqua", 15, 0x55FFFF),
			new Cosmetic("name_lime", "name", "Lime", 15, 0x7CFF4F),
			new Cosmetic("name_pink", "name", "Hot pink", 15, 0xFF5FC8),
			new Cosmetic("name_crimson", "name", "Crimson", 15, 0xE0303A),
			new Cosmetic("name_lavender", "name", "Lavender", 15, 0xB79CFF),
			new Cosmetic("name_sunset", "name", "Sunset (gradient)", 30, 0xFF5E62),
			new Cosmetic("name_ocean", "name", "Ocean (gradient)", 30, 0x2E8BFF),
			new Cosmetic("name_toxic", "name", "Toxic (gradient)", 30, 0xB6FF3B),
			new Cosmetic("name_candy", "name", "Candy (gradient)", 30, 0xFF77E9),
			new Cosmetic("name_rainbow", "name", "Rainbow (animated)", 55, 0xFF4040),
			new Cosmetic("name_aurora", "name", "Aurora (animated)", 50, 0x3CF0C0),
			new Cosmetic("name_inferno", "name", "Inferno (animated)", 50, 0xFF3A1A),
			new Cosmetic("name_glow_gold", "name", "Golden glow", 70, 0xFFC93C),
			new Cosmetic("name_glow_ghost", "name", "Ghost glow", 70, 0x8FF3FF),
			new Cosmetic("name_glow_void", "name", "Void glow", 70, 0xB266FF));

	public static Upgrade upgrade(String id) {
		return UPGRADES.stream().filter(u -> u.id.equals(id)).findFirst().orElse(null);
	}

	public static Cosmetic cosmetic(String id) {
		return COSMETICS.stream().filter(c -> c.id.equals(id)).findFirst().orElse(null);
	}

	// ------------------------------------------------------------------ saved data

	static final class Stats {
		int runs;
		int ended;
		int won;
		long gambled;
		int bestUnboxTier = -1;
		String bestUnbox = "";
		int bosses;
	}

	static final class Profile {
		String name = "";
		long eidolons;
		long earned;
		Map<String, Integer> upgrades = new HashMap<>();
		Set<String> owned = new HashSet<>();
		String trail = "";
		String title = "";
		String ribbon = "";
		String nameStyle = "";
		Stats stats = new Stats();
	}

	static final class Record {
		long ticks;
		String players = "";
		int run;
	}

	static final class Data {
		Map<String, Profile> players = new HashMap<>();
		Record longestRun = new Record();
		/** Who ended the last run (shown as "Run Ender" for the next one). */
		String runEnder = "";
	}

	/** Bets for the current run (in the world folder: a new world starts a new book). */
	static final class Book {
		/** player uuid -> [option, amount]; option = a player's uuid, or "nobody" (we finish the run). */
		Map<String, String[]> bets = new LinkedHashMap<>();
		boolean settled;
		/** uuids that got this run's starter perks */
		Set<String> starters = new HashSet<>();
		boolean victoryPaid;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Data data = new Data();
	private static Book book = new Book();
	private static Path file;
	private static Path bookFile;
	private static MinecraftServer server;

	/** Name titles shown above heads and in chat (both sides; the client fills it from the Titles payload). */
	public static final Map<UUID, Component> TITLES = new ConcurrentHashMap<>();
	/** Name colours (NameStyles ids), both sides; the client fills it from the Titles payload. */
	public static final Map<UUID, String> NAME_STYLES = new ConcurrentHashMap<>();

	private Eidolons() {
	}

	private static Profile profile(Player p) {
		Profile pr = data.players.computeIfAbsent(p.getStringUUID(), k -> new Profile());
		pr.name = p.getGameProfile().getName();
		return pr;
	}

	public static int level(ServerPlayer p, String upgrade) {
		Profile pr = data.players.get(p.getStringUUID());
		return pr == null ? 0 : pr.upgrades.getOrDefault(upgrade, 0);
	}

	/** Anyone's Eidolons, online or not. */
	static long balance(UUID id) {
		Profile pr = data.players.get(id.toString());
		return pr == null ? 0 : pr.eidolons;
	}

	/** Sets anyone's Eidolons (ops, CurrencyAdmin). Doesn't count as earned. */
	static void setBalance(UUID id, String name, long amount) {
		Profile pr = data.players.computeIfAbsent(id.toString(), k -> new Profile());
		if (name != null && !name.isEmpty() && (pr.name == null || pr.name.isEmpty())) {
			pr.name = name;
		}
		pr.eidolons = Math.max(0, amount);
		save();
		ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(id);
		if (p != null) {
			sendState(p);
		}
	}

	/** Everyone with an Eidolon profile, by UUID, with the name they last played as. */
	static Map<UUID, String> knownNames() {
		Map<UUID, String> out = new LinkedHashMap<>();
		data.players.forEach((k, pr) -> {
			try {
				out.put(UUID.fromString(k), pr.name == null ? "" : pr.name);
			} catch (IllegalArgumentException ignored) {
			}
		});
		return out;
	}

	static long balance(ServerPlayer p) {
		return profile(p).eidolons;
	}

	// ------------------------------------------------------------------ payloads

	public record HallEntry(String label, String holder, String value) {
	}

	/** Server to client: your Eidolons, upgrades, cosmetics, and the hall of fame. */
	public record State(long eidolons, Map<String, Integer> upgrades, List<String> owned, String trail, String title, List<HallEntry> hall)
			implements CustomPacketPayload {
		public static final Type<State> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "eidolon_state"));
		public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.ofMember((s, buf) -> {
			buf.writeVarLong(s.eidolons);
			buf.writeVarInt(s.upgrades.size());
			s.upgrades.forEach((k, v) -> {
				buf.writeUtf(k, 64);
				buf.writeVarInt(v);
			});
			buf.writeVarInt(s.owned.size());
			s.owned.forEach(o -> buf.writeUtf(o, 64));
			buf.writeUtf(s.trail, 64);
			buf.writeUtf(s.title, 64);
			buf.writeVarInt(s.hall.size());
			for (HallEntry h : s.hall) {
				buf.writeUtf(h.label, 64);
				buf.writeUtf(h.holder, 128);
				buf.writeUtf(h.value, 128);
			}
		}, buf -> {
			long e = buf.readVarLong();
			Map<String, Integer> up = new HashMap<>();
			int n = buf.readVarInt();
			for (int i = 0; i < n; i++) {
				up.put(buf.readUtf(64), buf.readVarInt());
			}
			List<String> owned = new ArrayList<>();
			n = buf.readVarInt();
			for (int i = 0; i < n; i++) {
				owned.add(buf.readUtf(64));
			}
			String trail = buf.readUtf(64);
			String title = buf.readUtf(64);
			List<HallEntry> hall = new ArrayList<>();
			n = buf.readVarInt();
			for (int i = 0; i < n; i++) {
				hall.add(new HallEntry(buf.readUtf(64), buf.readUtf(128), buf.readUtf(128)));
			}
			return new State(e, up, owned, trail, title, hall);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	public static final int BUY_UPGRADE = 0;
	public static final int BUY_COSMETIC = 1;
	public static final int EQUIP = 2;
	public static final int UNEQUIP_TRAIL = 3;
	public static final int UNEQUIP_TITLE = 4;
	public static final int REQUEST = 5;
	public static final int UNEQUIP_RIBBON = 6;
	public static final int UNEQUIP_NAME = 7;

	/** Client to server: buy / equip something, or ask for the state. */
	public record Action(int action, String id) implements CustomPacketPayload {
		public static final Type<Action> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "eidolon_action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.ofMember((a, buf) -> {
			buf.writeVarInt(a.action);
			buf.writeUtf(a.id, 64);
		}, buf -> new Action(buf.readVarInt(), buf.readUtf(64)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Server to client: everyone's title (shown above heads, in chat and in the tab list). */
	public record Titles(Map<UUID, String> text, Map<UUID, Integer> color, Map<UUID, String> names) implements CustomPacketPayload {
		public static final Type<Titles> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "eidolon_titles"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Titles> CODEC = StreamCodec.ofMember((t, buf) -> {
			buf.writeVarInt(t.text.size());
			t.text.forEach((k, v) -> {
				buf.writeUUID(k);
				buf.writeUtf(v, 64);
				buf.writeInt(t.color.getOrDefault(k, 0xFFFFFF));
			});
			buf.writeVarInt(t.names.size());
			t.names.forEach((k, v) -> {
				buf.writeUUID(k);
				buf.writeUtf(v, 64);
			});
		}, buf -> {
			Map<UUID, String> text = new HashMap<>();
			Map<UUID, Integer> color = new HashMap<>();
			int n = buf.readVarInt();
			for (int i = 0; i < n; i++) {
				UUID id = buf.readUUID();
				text.put(id, buf.readUtf(64));
				color.put(id, buf.readInt());
			}
			Map<UUID, String> names = new HashMap<>();
			n = buf.readVarInt();
			for (int i = 0; i < n; i++) {
				names.put(buf.readUUID(), buf.readUtf(64));
			}
			return new Titles(text, color, names);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Server to client: who is wearing which ribbon trail (everyone draws everyone's). */
	public record Ribbons(Map<UUID, String> worn) implements CustomPacketPayload {
		public static final Type<Ribbons> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "eidolon_ribbons"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Ribbons> CODEC = StreamCodec.ofMember((r, buf) -> {
			buf.writeVarInt(r.worn.size());
			r.worn.forEach((k, v) -> {
				buf.writeUUID(k);
				buf.writeUtf(v, 64);
			});
		}, buf -> {
			Map<UUID, String> worn = new HashMap<>();
			int n = buf.readVarInt();
			for (int i = 0; i < n; i++) {
				worn.put(buf.readUUID(), buf.readUtf(64));
			}
			return new Ribbons(worn);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Server to client: the betting book for this run (shown on the ready screen). */
	public record Bets(boolean open, List<String> keys, List<String> labels, List<Long> pots, String myOption, long myAmount, long eidolons)
			implements CustomPacketPayload {
		public static final Type<Bets> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "eidolon_bets"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Bets> CODEC = StreamCodec.ofMember((b, buf) -> {
			buf.writeBoolean(b.open);
			buf.writeVarInt(b.keys.size());
			for (int i = 0; i < b.keys.size(); i++) {
				buf.writeUtf(b.keys.get(i), 64);
				buf.writeUtf(b.labels.get(i), 64);
				buf.writeVarLong(b.pots.get(i));
			}
			buf.writeUtf(b.myOption, 64);
			buf.writeVarLong(b.myAmount);
			buf.writeVarLong(b.eidolons);
		}, buf -> {
			boolean open = buf.readBoolean();
			int n = Math.min(buf.readVarInt(), 64);
			List<String> keys = new ArrayList<>();
			List<String> labels = new ArrayList<>();
			List<Long> pots = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				keys.add(buf.readUtf(64));
				labels.add(buf.readUtf(64));
				pots.add(buf.readVarLong());
			}
			return new Bets(open, keys, labels, pots, buf.readUtf(64), buf.readVarLong(), buf.readVarLong());
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client to server: bet `amount` Eidolons on `option` (replaces your earlier bet; 0 takes it back). */
	public record PlaceBet(String option, long amount) implements CustomPacketPayload {
		public static final Type<PlaceBet> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "eidolon_bet"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PlaceBet> CODEC = StreamCodec.ofMember((b, buf) -> {
			buf.writeUtf(b.option, 64);
			buf.writeVarLong(b.amount);
		}, buf -> new PlaceBet(buf.readUtf(64), buf.readVarLong()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	static void register(PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c, PayloadTypeRegistry<RegistryFriendlyByteBuf> c2s) {
		s2c.register(State.ID, State.CODEC);
		s2c.register(Titles.ID, Titles.CODEC);
		s2c.register(Ribbons.ID, Ribbons.CODEC);
		s2c.register(Bets.ID, Bets.CODEC);
		c2s.register(Action.ID, Action.CODEC);
		c2s.register(PlaceBet.ID, PlaceBet.CODEC);
	}

	// ------------------------------------------------------------------ lifecycle

	static void init() {
		file = FMLPaths.GAMEDIR.get().resolve("bubble-eidolons.json");
		load();
		var bus = NeoForge.EVENT_BUS;
		bus.addListener((ServerStartedEvent e) -> {
			server = e.getServer();
			bookFile = server.getWorldPath(LevelResource.ROOT).resolve("bubble-run-book.json");
			loadBook();
			rebuildTitles();
		});
		bus.addListener((ServerStoppedEvent e) -> {
			save();
			saveBook();
			server = null;
		});
		bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
			if (e.getEntity() instanceof ServerPlayer p) {
				p.server.execute(() -> {
					profile(p);
					rebuildTitles();
					sendState(p);
					sendBets();
					if (!Lobby.active()) {
						giveStarter(p);
					}
				});
			}
		});
		// Titles in chat, the tab list and above heads (the client runs the same listener with its own map)
		bus.addListener((PlayerEvent.NameFormat e) -> {
			Component name = styledName(e.getEntity(), e.getDisplayname());
			Component t = TITLES.get(e.getEntity().getUUID());
			if (t != null) {
				e.setDisplayname(Component.empty().append(t).append(" ").append(name));
			} else if (name != e.getDisplayname()) {
				e.setDisplayname(name);
			}
		});
		bus.addListener((PlayerEvent.TabListNameFormat e) -> {
			Component t = TITLES.get(e.getEntity().getUUID());
			Component base = e.getDisplayName() != null ? e.getDisplayName() : e.getEntity().getName();
			Component name = styledName(e.getEntity(), base);
			if (t != null) {
				e.setDisplayName(Component.empty().append(t).append(" ").append(name));
			} else if (name != base) {
				e.setDisplayName(name);
			}
		});
		bus.addListener((ServerTickEvent.Post e) -> {
			if (e.getServer().getTickCount() % 3 == 0) {
				trails(e.getServer());
			}
		});
		bus.addListener((LivingDeathEvent e) -> {
			if (!e.getEntity().level().isClientSide && e.getSource().getEntity() instanceof ServerPlayer killer) {
				EntityType<?> t = e.getEntity().getType();
				if ((t.is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES) || t == EntityType.WITHER || t == EntityType.ENDER_DRAGON || t == EntityType.WARDEN)
						&& !Bosses.handles(e.getEntity())) { // shared-reward bosses count for every helper (bossKill)
					profile(killer).stats.bosses++;
					save();
				}
			}
		});
	}

	// ------------------------------------------------------------------ buying

	static void action(ServerPlayer p, int action, String id) {
		Profile pr = profile(p);
		switch (action) {
			case BUY_UPGRADE -> {
				Upgrade u = upgrade(id);
				int lvl = pr.upgrades.getOrDefault(id, 0);
				if (u == null || lvl >= u.costs.length) {
					break;
				}
				int cost = u.costs[lvl];
				if (pr.eidolons < cost) {
					p.displayClientMessage(Component.literal("Not enough Eidolons (" + pr.eidolons + " / " + cost + ")").withStyle(ChatFormatting.RED), true);
					break;
				}
				pr.eidolons -= cost;
				pr.upgrades.put(id, lvl + 1);
				p.displayClientMessage(Component.literal(u.name + " is now level " + (lvl + 1)).withStyle(ChatFormatting.LIGHT_PURPLE), true);
				p.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.2f);
				RunModifiers.applyAttributes(p);
			}
			case BUY_COSMETIC -> {
				Cosmetic c = cosmetic(id);
				if (c == null || pr.owned.contains(id)) {
					break;
				}
				if (pr.eidolons < c.cost) {
					p.displayClientMessage(Component.literal("Not enough Eidolons (" + pr.eidolons + " / " + c.cost + ")").withStyle(ChatFormatting.RED), true);
					break;
				}
				pr.eidolons -= c.cost;
				pr.owned.add(id);
				equip(pr, c);
				p.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.4f);
			}
			case EQUIP -> {
				Cosmetic c = cosmetic(id);
				if (c != null && pr.owned.contains(id)) {
					equip(pr, c);
				}
			}
			case UNEQUIP_TRAIL -> pr.trail = "";
			case UNEQUIP_TITLE -> pr.title = "";
			case UNEQUIP_RIBBON -> pr.ribbon = "";
			case UNEQUIP_NAME -> pr.nameStyle = "";
			default -> {
			}
		}
		save();
		rebuildTitles();
		sendState(p);
	}

	private static void equip(Profile pr, Cosmetic c) {
		if (c.kind.equals("trail")) {
			pr.trail = c.id;
		} else if (c.kind.equals("ribbon")) {
			pr.ribbon = c.id;
		} else if (c.kind.equals("name")) {
			pr.nameStyle = c.id;
		} else {
			pr.title = c.id;
		}
	}

	// ------------------------------------------------------------------ run start / end

	/** Allowance and Head Start for this run, once per player per world. */
	static void giveStarter(ServerPlayer p) {
		if (server == null || !book.starters.add(p.getStringUUID())) {
			return;
		}
		saveBook();
		int allowance = level(p, "allowance");
		if (allowance > 0) {
			Matter.add(p, 50.0 * allowance);
		}
		int kit = level(p, "kit");
		List<ItemStack> items = new ArrayList<>();
		if (kit >= 1) {
			items.add(new ItemStack(Items.STONE_SWORD));
			items.add(new ItemStack(Items.STONE_PICKAXE));
			items.add(new ItemStack(Items.STONE_AXE));
			items.add(new ItemStack(Items.BREAD, 16));
		}
		if (kit >= 2) {
			items.add(new ItemStack(Items.IRON_SWORD));
			items.add(new ItemStack(Items.IRON_PICKAXE));
			items.add(new ItemStack(Items.TORCH, 32));
		}
		if (kit >= 3) {
			items.add(new ItemStack(Items.IRON_HELMET));
			items.add(new ItemStack(Items.IRON_CHESTPLATE));
			items.add(new ItemStack(Items.IRON_LEGGINGS));
			items.add(new ItemStack(Items.IRON_BOOTS));
		}
		for (ItemStack s : items) {
			if (!p.getInventory().add(s)) {
				p.drop(s, false);
			}
		}
		if (allowance > 0 || kit > 0) {
			p.sendSystemMessage(Component.literal("✦ Your Eidolon perks: " + (allowance > 0 ? "+" + 50 * allowance + " Strange Matter" : "")
					+ (allowance > 0 && kit > 0 ? ", " : "") + (kit > 0 ? "a Head Start kit" : "")).withStyle(ChatFormatting.LIGHT_PURPLE));
		}
	}

	/** Lobby finished: everyone online gets their starter perks now. */
	static void runStarted() {
		if (server != null) {
			server.getPlayerList().getPlayers().forEach(Eidolons::giveStarter);
			sendBets();
		}
	}

	/** The game mode was completed: the victory bonus now (once), and the bets on "nobody" win. */
	static void victory(int stagesCleared) {
		if (server == null || book.victoryPaid) {
			return;
		}
		book.victoryPaid = true;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Profile pr = profile(p);
			pr.stats.won++;
			give(p, 10, "for completing the run");
		}
		settle("nobody");
		save();
		saveBook();
	}

	/**
	 * The run is really over (a new world is about to be made): pay out Eidolons for the Strange Matter held and the
	 * stages cleared, settle the bets, update the hall of fame. `ender` = whose death ended it (null if nobody's).
	 */
	static void runFinished(ServerPlayer ender, long runTicks, int stagesCleared, int run) {
		if (server == null) {
			return;
		}
		List<String> names = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Profile pr = profile(p);
			pr.stats.runs++;
			names.add(p.getGameProfile().getName());
			long fromMatter = (long) Math.floor(Matter.balance(p) / 100.0);
			long amount = fromMatter + 2L * stagesCleared;
			if (amount > 0) {
				give(p, amount, "(" + fromMatter + " from Strange Matter, " + 2 * stagesCleared + " from stages)");
			}
		}
		if (ender != null) {
			profile(ender).stats.ended++;
			data.runEnder = ender.getStringUUID();
			settle(ender.getStringUUID());
		} else {
			data.runEnder = "";
			settle(null);
		}
		if (!names.isEmpty() && runTicks > data.longestRun.ticks) {
			data.longestRun.ticks = runTicks;
			data.longestRun.players = String.join(", ", names);
			data.longestRun.run = run;
			server.getPlayerList().broadcastSystemMessage(Component.literal("🏆 New record: longest run (" + BubbleServer.formatDuration((int) Math.min(Integer.MAX_VALUE, runTicks)) + ")!")
					.withStyle(ChatFormatting.GOLD), false);
		}
		save();
	}

	private static void give(ServerPlayer p, long amount, String why) {
		Profile pr = profile(p);
		pr.eidolons += amount;
		pr.earned += amount;
		p.sendSystemMessage(Component.literal("✦ +" + amount + " Eidolons " + why).withStyle(ChatFormatting.LIGHT_PURPLE));
		sendState(p);
	}

	// ------------------------------------------------------------------ hall of fame hooks

	/** Bosses: `p` helped take one down (a boss kill in the hall of fame, plus its Eidolons). */
	static void bossKill(ServerPlayer p, long amount, String boss) {
		profile(p).stats.bosses++;
		if (amount > 0) {
			give(p, amount, "for taking down " + boss);
		}
		save();
	}

	static void recordGamble(ServerPlayer p, long cost) {
		profile(p).stats.gambled += cost;
		save();
	}

	static void recordUnbox(ServerPlayer p, int tier, String item) {
		Stats s = profile(p).stats;
		if (tier > s.bestUnboxTier) {
			s.bestUnboxTier = tier;
			s.bestUnbox = item;
			save();
		}
	}

	private static List<HallEntry> hall() {
		List<HallEntry> out = new ArrayList<>();
		if (data.longestRun.ticks > 0) {
			out.add(new HallEntry("Longest run", data.longestRun.players, BubbleServer.formatDuration((int) Math.min(Integer.MAX_VALUE, data.longestRun.ticks))
					+ " (run #" + data.longestRun.run + ")"));
		}
		best(out, "Most runs won", s -> s.won, "");
		best(out, "Most runs ended (shame!)", s -> s.ended, "");
		best(out, "Biggest gambler", s -> s.gambled, " Strange Matter");
		best(out, "Most bosses killed", s -> s.bosses, "");
		Profile lucky = null;
		for (Profile pr : data.players.values()) {
			if (pr.stats.bestUnboxTier >= 0 && (lucky == null || pr.stats.bestUnboxTier > lucky.stats.bestUnboxTier)) {
				lucky = pr;
			}
		}
		if (lucky != null) {
			out.add(new HallEntry("Luckiest unboxing", lucky.name, Gamble.TIERS[lucky.stats.bestUnboxTier] + ": " + lucky.stats.bestUnbox));
		}
		Profile rich = data.players.values().stream().max((a, b) -> Long.compare(a.earned, b.earned)).orElse(null);
		if (rich != null && rich.earned > 0) {
			out.add(new HallEntry("Most Eidolons earned", rich.name, String.valueOf(rich.earned)));
		}
		Profile ender = data.players.get(data.runEnder);
		if (ender != null) {
			out.add(new HallEntry("Ended the last run", ender.name, "\"Run Ender\" until the next run ends"));
		}
		return out;
	}

	private static void best(List<HallEntry> out, String label, java.util.function.ToLongFunction<Stats> f, String unit) {
		Profile top = null;
		for (Profile pr : data.players.values()) {
			if (f.applyAsLong(pr.stats) > 0 && (top == null || f.applyAsLong(pr.stats) > f.applyAsLong(top.stats))) {
				top = pr;
			}
		}
		if (top != null) {
			out.add(new HallEntry(label, top.name, f.applyAsLong(top.stats) + unit));
		}
	}

	// ------------------------------------------------------------------ betting

	static void placeBet(ServerPlayer p, String option, long amount) {
		if (!Lobby.active() || book.settled) {
			p.displayClientMessage(Component.literal("Bets are only taken in the lobby before a run starts.").withStyle(ChatFormatting.RED), true);
			return;
		}
		Profile pr = profile(p);
		String[] old = book.bets.remove(p.getStringUUID());
		if (old != null) {
			pr.eidolons += Long.parseLong(old[1]); // take back the old bet first
		}
		amount = Math.max(0, amount);
		boolean valid = option.equals("nobody") || server.getPlayerList().getPlayer(UUID.fromString(option)) != null;
		if (amount > 0 && valid) {
			if (pr.eidolons < amount) {
				p.displayClientMessage(Component.literal("You only have " + pr.eidolons + " Eidolons.").withStyle(ChatFormatting.RED), true);
			} else {
				pr.eidolons -= amount;
				book.bets.put(p.getStringUUID(), new String[]{option, String.valueOf(amount)});
				String on = option.equals("nobody") ? "nobody ending the run" : server.getPlayerList().getPlayer(UUID.fromString(option)).getGameProfile().getName() + " ending the run";
				server.getPlayerList().broadcastSystemMessage(Component.literal("🎲 " + p.getGameProfile().getName() + " bet " + amount + " Eidolons on " + on)
						.withStyle(ChatFormatting.LIGHT_PURPLE), false);
			}
		}
		save();
		saveBook();
		sendState(p);
		sendBets();
	}

	/** Pays the bets: winners split the whole pot by stake. No winners (or no ender) = everyone gets their bet back. */
	private static void settle(String winner) {
		if (book.settled || book.bets.isEmpty()) {
			book.settled = true;
			return;
		}
		book.settled = true;
		long pot = 0;
		long winning = 0;
		for (String[] b : book.bets.values()) {
			pot += Long.parseLong(b[1]);
			if (b[0].equals(winner)) {
				winning += Long.parseLong(b[1]);
			}
		}
		for (Map.Entry<String, String[]> e : book.bets.entrySet()) {
			Profile pr = data.players.get(e.getKey());
			if (pr == null) {
				continue;
			}
			long stake = Long.parseLong(e.getValue()[1]);
			long back = winning == 0 ? stake : e.getValue()[0].equals(winner) ? Math.round(pot * (stake / (double) winning)) : 0;
			pr.eidolons += back;
			ServerPlayer online = server == null ? null : server.getPlayerList().getPlayer(UUID.fromString(e.getKey()));
			if (online != null) {
				online.sendSystemMessage(Component.literal(winning == 0 ? "🎲 Nobody called it: your " + stake + " Eidolons came back."
						: back > 0 ? "🎲 You called it! +" + back + " Eidolons." : "🎲 Your bet of " + stake + " Eidolons is gone.")
						.withStyle(back > stake ? ChatFormatting.GOLD : ChatFormatting.LIGHT_PURPLE));
				sendState(online);
			}
		}
		saveBook();
	}

	static void sendBets() {
		if (server == null) {
			return;
		}
		List<String> keys = new ArrayList<>();
		List<String> labels = new ArrayList<>();
		for (ServerPlayer q : server.getPlayerList().getPlayers()) {
			keys.add(q.getStringUUID());
			labels.add(q.getGameProfile().getName());
		}
		keys.add("nobody");
		labels.add("Nobody (we finish)");
		List<Long> pots = new ArrayList<>();
		for (String k : keys) {
			pots.add(book.bets.values().stream().filter(b -> b[0].equals(k)).mapToLong(b -> Long.parseLong(b[1])).sum());
		}
		boolean open = Lobby.active() && !book.settled;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(p, Bets.ID)) {
				String[] mine = book.bets.get(p.getStringUUID());
				ServerPlayNetworking.send(p, new Bets(open, keys, labels, pots, mine == null ? "" : mine[0], mine == null ? 0 : Long.parseLong(mine[1]),
						profile(p).eidolons));
			}
		}
	}

	// ------------------------------------------------------------------ cosmetics

	static void rebuildTitles() {
		if (server == null) {
			return;
		}
		Map<UUID, String> text = new HashMap<>();
		Map<UUID, Integer> color = new HashMap<>();
		Map<UUID, String> ribbons = new HashMap<>();
		Map<UUID, String> names = new HashMap<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Profile pr = profile(p);
			if (!pr.nameStyle.isEmpty() && NameStyles.spec(pr.nameStyle) != null) {
				names.put(p.getUUID(), pr.nameStyle);
			}
			if (!pr.ribbon.isEmpty() && cosmetic(pr.ribbon) != null) {
				ribbons.put(p.getUUID(), pr.ribbon);
			}
			String t = null;
			int c = 0xFFFFFF;
			if (p.getStringUUID().equals(data.runEnder)) {
				t = "Run Ender";
				c = 0xC02020;
			} else if (!pr.title.isEmpty() && cosmetic(pr.title) != null) {
				t = cosmetic(pr.title).name;
				c = cosmetic(pr.title).color;
			}
			if (t != null) {
				text.put(p.getUUID(), t);
				color.put(p.getUUID(), c);
			}
		}
		applyTitles(text, color, names);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			p.refreshDisplayName();
			p.refreshTabListName();
			if (ServerPlayNetworking.canSend(p, Titles.ID)) {
				ServerPlayNetworking.send(p, new Titles(text, color, names));
			}
			if (ServerPlayNetworking.canSend(p, Ribbons.ID)) {
				ServerPlayNetworking.send(p, new Ribbons(ribbons));
			}
		}
	}

	/** Used on both sides. */
	public static void applyTitles(Map<UUID, String> text, Map<UUID, Integer> color, Map<UUID, String> names) {
		NAME_STYLES.clear();
		NAME_STYLES.putAll(names);
		TITLES.clear();
		text.forEach((id, t) -> TITLES.put(id, Component.literal("[" + t + "]").withStyle(s -> s.withColor(color.getOrDefault(id, 0xFFFFFF)))));
	}

	/** The player's name in their name colour (if they have one and nothing else renamed them). */
	private static Component styledName(net.minecraft.world.entity.player.Player p, Component name) {
		String id = NAME_STYLES.get(p.getUUID());
		String plain = p.getGameProfile().getName();
		if (id == null || name == null || !name.getString().equals(plain)) {
			return name;
		}
		return NameStyles.style(plain, id, name.getStyle());
	}

	private static void trails(MinecraftServer s) {
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			Profile pr = data.players.get(p.getStringUUID());
			if (pr == null || pr.trail.isEmpty() || p.isSpectator() || p.isInvisible()) {
				continue;
			}
			boolean moving = p.getDeltaMovement().horizontalDistanceSqr() > 0.0004 || !p.onGround();
			if (!moving) {
				continue;
			}
			ParticleOptions particle = switch (pr.trail) {
				case "trail_hearts" -> ParticleTypes.HEART;
				case "trail_flame" -> ParticleTypes.FLAME;
				case "trail_notes" -> ParticleTypes.NOTE;
				case "trail_soul" -> ParticleTypes.SOUL_FIRE_FLAME;
				case "trail_end" -> ParticleTypes.END_ROD;
				case "trail_cherry" -> ParticleTypes.CHERRY_LEAVES;
				case "trail_rainbow" -> {
					int rgb = Mth.hsvToRgb((s.getTickCount() % 60) / 60f, 1f, 1f);
					yield new DustParticleOptions(new Vector3f(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f), 1.2f);
				}
				default -> null;
			};
			if (particle != null) {
				ServerLevel level = p.serverLevel();
				level.sendParticles(particle, p.getX(), p.getY() + 0.15, p.getZ(), 2, 0.2, 0.05, 0.2, 0.0);
			}
		}
	}

	// ------------------------------------------------------------------ state + files

	static void sendState(ServerPlayer p) {
		if (!ServerPlayNetworking.canSend(p, State.ID)) {
			return;
		}
		Profile pr = profile(p);
		ServerPlayNetworking.send(p, new State(pr.eidolons, new HashMap<>(pr.upgrades), new ArrayList<>(pr.owned), pr.trail, pr.title, hall()));
	}

	/** Haggler: what selling pays, multiplied. */
	static double sellBonus(ServerPlayer p) {
		return 1 + 0.05 * level(p, "haggler");
	}

	/** /bubble eidolons give <player> <amount> */
	static void adminGive(ServerPlayer p, long amount) {
		give(p, amount, "(from an op)");
		save();
	}

	private static void load() {
		try {
			if (Files.exists(file)) {
				Data d = GSON.fromJson(Files.readString(file), Data.class);
				if (d != null) {
					data = d;
				}
			}
		} catch (Exception e) {
			Bubble.LOG.error("Could not read bubble-eidolons.json", e);
		}
		if (data.players == null) {
			data.players = new HashMap<>();
		}
		if (data.longestRun == null) {
			data.longestRun = new Record();
		}
		if (data.runEnder == null) {
			data.runEnder = "";
		}
		data.players.values().forEach(pr -> {
			if (pr.upgrades == null) {
				pr.upgrades = new HashMap<>();
			}
			if (pr.owned == null) {
				pr.owned = new HashSet<>();
			}
			if (pr.stats == null) {
				pr.stats = new Stats();
			}
			if (pr.trail == null) {
				pr.trail = "";
			}
			if (pr.title == null) {
				pr.title = "";
			}
			if (pr.ribbon == null) {
				pr.ribbon = "";
			}
			if (pr.nameStyle == null) {
				pr.nameStyle = "";
			}
		});
	}

	static void save() {
		try {
			Files.writeString(file, GSON.toJson(data));
		} catch (Exception e) {
			Bubble.LOG.error("Could not save bubble-eidolons.json", e);
		}
	}

	private static void loadBook() {
		book = new Book();
		try {
			if (Files.exists(bookFile)) {
				Book b = GSON.fromJson(Files.readString(bookFile), Book.class);
				if (b != null) {
					book = b;
				}
			}
		} catch (Exception e) {
			Bubble.LOG.error("Could not read {}", bookFile, e);
		}
		if (book.bets == null) {
			book.bets = new LinkedHashMap<>();
		}
		if (book.starters == null) {
			book.starters = new HashSet<>();
		}
	}

	private static void saveBook() {
		if (bookFile == null) {
			return;
		}
		try {
			Files.writeString(bookFile, GSON.toJson(book));
		} catch (Exception e) {
			Bubble.LOG.error("Could not save {}", bookFile, e);
		}
	}
}
