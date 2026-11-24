package moonahmc.bubble;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import moonahmc.bubble.net.PayloadTypeRegistry;
import moonahmc.bubble.net.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Case opening (the shop's Gamble tab), CS:GO style. A case has five rarity tiers with fixed odds (common 79.92%,
 * uncommon 15.98%, rare 3.2%, epic 0.64%, legendary 0.26%), each with its own item pool. Opening one sends the player
 * a reel of 60 items with the winner near the end; the screen spins it, and the item arrives when the reel stops.
 * Cases live in config/bubble-cases.json; items from mods that aren't installed are left out.
 */
public final class Gamble {
	public static final String[] TIERS = {"Common", "Uncommon", "Rare", "Epic", "Legendary"};
	public static final int[] TIER_COLORS = {0xFF4B69FF, 0xFF8847FF, 0xFFD32CE6, 0xFFEB4B4B, 0xFFE4AE39};
	/** Odds per tier, in hundredths of a percent. */
	private static final int[] ODDS = {7992, 1598, 320, 64, 26};
	static final int REEL = 60;
	static final int WINNER = 52;
	/** With several reels, each stops this many ticks after the one before. */
	public static final int STAGGER_TICKS = 5;
	/** How much each point of Luck multiplies the chance of each tier (uncommon .. legendary). */
	private static final double[] LUCK_BOOST = {0, 0.08, 0.15, 0.25, 0.35};
	/** How long the reel spins on the client, then the item is handed over. */
	public static final int SPIN_TICKS = 130;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** One possible drop: an item, or a command with an item to show for it. */
	record Drop(ItemStack show, String command) {
	}

	/** odds: one weight per tier (shown as a share of the total). */
	record Case(String id, String name, List<List<Drop>> tiers, int color, double[] odds) {
	}

	/** One reel: the items scrolling past, their tiers, and which one it stops on. */
	public record Reel(List<ItemStack> items, List<Integer> tiers, int winner) {
	}

	/** Server to client: the reels for the case(s) you just opened (1, or 5 with shift-click). */
	public record Roll(String caseName, List<Reel> reels, int section, int index, int price, float luck) implements CustomPacketPayload {
		public static final Type<Roll> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "case_roll"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Roll> CODEC = StreamCodec.ofMember((r, buf) -> {
			buf.writeUtf(r.caseName, 64);
			buf.writeVarInt(r.reels.size());
			for (Reel reel : r.reels) {
				buf.writeVarInt(reel.items.size());
				for (int i = 0; i < reel.items.size(); i++) {
					ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, reel.items.get(i));
					buf.writeVarInt(reel.tiers.get(i));
				}
				buf.writeVarInt(reel.winner);
			}
			buf.writeVarInt(r.section);
			buf.writeVarInt(r.index);
			buf.writeVarInt(r.price);
			buf.writeFloat(r.luck);
		}, buf -> {
			String name = buf.readUtf(64);
			int reelCount = Math.min(buf.readVarInt(), 10);
			List<Reel> reels = new ArrayList<>();
			for (int k = 0; k < reelCount; k++) {
				int n = Math.min(buf.readVarInt(), 128);
				List<ItemStack> items = new ArrayList<>();
				List<Integer> tiers = new ArrayList<>();
				for (int i = 0; i < n; i++) {
					items.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
					tiers.add(buf.readVarInt());
				}
				reels.add(new Reel(items, tiers, buf.readVarInt()));
			}
			return new Roll(name, reels, buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readFloat());
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client to server: hurry up (the speed-up button): hand over what I won now. */
	public record Skip() implements CustomPacketPayload {
		public static final Type<Skip> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "case_skip"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Skip> CODEC = StreamCodec.unit(new Skip());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	private record Pending(UUID player, Drop drop, int tier, String caseName, long due) {
	}

	private static final Map<String, Case> cases = new LinkedHashMap<>();
	private static final List<Pending> pending = new ArrayList<>();
	private static MinecraftServer server;

	private Gamble() {
	}

	static void register(PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c, PayloadTypeRegistry<RegistryFriendlyByteBuf> c2s) {
		s2c.register(Roll.ID, Roll.CODEC);
		c2s.register(Skip.ID, Skip.CODEC);
		s2c.register(CaseList.ID, CaseList.CODEC);
		c2s.register(CaseEdit.ID, CaseEdit.CODEC);
	}

	static void init() {
		ServerPlayNetworking.registerGlobalReceiver(CaseEdit.ID, (payload, context) -> context.server().execute(() -> editCase(context.player(), payload)));
		var bus = NeoForge.EVENT_BUS;
		bus.addListener((ServerStartedEvent e) -> {
			server = e.getServer();
			load();
		});
		bus.addListener((ServerTickEvent.Post e) -> deliverDue(e.getServer(), false));
		// Leaving mid-spin: get the item right away (it's saved with you).
		bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
			Iterator<Pending> it = pending.iterator();
			while (it.hasNext()) {
				Pending p = it.next();
				if (p.player.equals(e.getEntity().getUUID()) && e.getEntity() instanceof ServerPlayer sp) {
					it.remove();
					deliver(sp, p, false);
				}
			}
		});
	}

	static boolean exists(String id) {
		return cases.containsKey(id);
	}

	static java.util.Set<String> ids() {
		return cases.keySet();
	}

	static String caseName(String id) {
		Case c = cases.get(id);
		return c != null ? c.name : id.isEmpty() ? "Case" : id + " case";
	}

	/** RGB, for the case item's band and name. */
	static int caseColor(String id) {
		Case c = cases.get(id);
		return c != null ? c.color : 0xE4AE39;
	}

	/** "color" in the case file: "#rrggbb" or a chat colour name; the built-in cases have their own. */
	private static int parseColor(JsonObject co, String id) {
		if (co.has("color")) {
			String v = co.get("color").getAsString().trim();
			try {
				if (v.startsWith("#")) {
					return Integer.parseInt(v.substring(1), 16) & 0xFFFFFF;
				}
				ChatFormatting f = ChatFormatting.getByName(v);
				if (f != null && f.getColor() != null) {
					return f.getColor();
				}
			} catch (NumberFormatException ignored) {
				// fall through to the default
			}
		}
		return switch (id) {
			case "mod" -> 0xFFAA00;
			case "progression" -> 0xC04CFF;
			case "weapons" -> 0xFF5555;
			case "twilight" -> 0x3FBF5A;
			default -> 0xE4AE39;
		};
	}

	/**
	 * Shop action "case:<id>". Opens `count` cases (1, or 5 with shift-click): charges, rolls and sends the reels.
	 * Your Luck (potions, gear, Apotheosis affixes...) makes the rarer tiers more likely for what you actually win.
	 */
	static boolean open(ServerPlayer p, String id, int price, int section, int index, int count) {
		Case c = cases.get(id);
		if (c == null) {
			p.displayClientMessage(Component.literal("That case doesn't exist (any more).").withStyle(ChatFormatting.RED), true);
			return false;
		}
		return open(p, c, price, section, index, count);
	}

	/**
	 * Shop action "attachment_case": a case made for the gun you're holding (or the first one you carry): every
	 * attachment that fits it and isn't already on it, rarer the better it is.
	 */
	static boolean openAttachmentCase(ServerPlayer p, int price, int section, int index, int count) {
		if (!Guns.available()) {
			p.displayClientMessage(Component.literal("Guns aren't installed on this server.").withStyle(ChatFormatting.RED), true);
			return false;
		}
		ItemStack gun = GunSupply.mainGun(p);
		if (gun.isEmpty()) {
			p.displayClientMessage(Component.literal("Hold the gun you want an attachment for.").withStyle(ChatFormatting.RED), true);
			p.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.6f, 1.2f);
			return false;
		}
		List<List<Drop>> tiers = new ArrayList<>();
		for (List<ItemStack> pool : GunSupply.attachmentsFor(gun)) {
			tiers.add(pool.stream().map(s -> new Drop(s, null)).toList());
		}
		if (tiers.stream().allMatch(List::isEmpty)) {
			p.displayClientMessage(Component.literal("Nothing else fits that gun.").withStyle(ChatFormatting.RED), true);
			p.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.6f, 1.2f);
			return false;
		}
		String name = "Attachment Case (" + GunSupply.gunName(gun).getString() + ")";
		return open(p, new Case("attachment", name, tiers, 0x9FD3FF, attachmentOdds()), price, section, index, count);
	}

	/** The Attachment Case's odds: from the cases file's "attachmentOdds" if it's there, otherwise the usual ones. */
	private static double[] attachmentOdds() {
		JsonObject root = casesRoot;
		JsonObject holder = new JsonObject();
		if (root != null && root.has("attachmentOdds")) {
			holder.add("odds", root.get("attachmentOdds"));
		}
		return parseOdds(holder);
	}

	/** A prize's name for chat (TACZ items only know theirs on the client). */
	static Component nameOf(ItemStack s) {
		if (Guns.available() && BuiltInRegistries.ITEM.getKey(s.getItem()).getNamespace().equals("tacz")) {
			return GunSupply.name(s);
		}
		return s.getHoverName();
	}

	private static boolean open(ServerPlayer p, Case c, int price, int section, int index, int count) {
		if (pending.stream().anyMatch(x -> x.player.equals(p.getUUID()))) {
			p.displayClientMessage(Component.literal("Wait for your case to finish spinning.").withStyle(ChatFormatting.YELLOW), true);
			return false;
		}
		count = Math.max(1, Math.min(5, count));
		long cost = (long) price * count;
		if (!Matter.add(p, -cost)) {
			p.displayClientMessage(Component.literal("Not enough Strange Matter (" + Matter.format(Matter.balance(p)) + " / " + cost + ")")
					.withStyle(ChatFormatting.RED), true);
			p.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.6f, 1.2f);
			return false;
		}
		Eidolons.recordGamble(p, cost);
		Goals.custom("case_opened");
		RandomSource rand = p.getRandom();
		float luck = (float) p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.LUCK);
		boolean modded = ServerPlayNetworking.canSend(p, Roll.ID);
		List<Reel> reels = new ArrayList<>();
		for (int k = 0; k < count; k++) {
			List<ItemStack> items = new ArrayList<>();
			List<Integer> tiers = new ArrayList<>();
			Drop win = null;
			int winTier = 0;
			for (int i = 0; i < REEL; i++) {
				int tier = rollTier(c, rand, i == WINNER ? luck : 0); // luck only counts for the prize, not the filler
				List<Drop> pool = c.tiers.get(tier);
				Drop d = pool.get(rand.nextInt(pool.size()));
				if (i == WINNER) {
					d = Condition.roll(p, d, rand); // maybe enchanted / Apothic / Factory New
				}
				items.add(d.show.copy());
				tiers.add(tier);
				if (i == WINNER) {
					win = d;
					winTier = tier;
				}
			}
			reels.add(new Reel(items, tiers, WINNER));
			long due = p.server.getTickCount() + (modded ? SPIN_TICKS + (long) STAGGER_TICKS * k : 0);
			pending.add(new Pending(p.getUUID(), win, winTier, c.name, due));
			Bubble.LOG.info("{} opened a {}: {} {} (luck {})", p.getGameProfile().getName(), c.name, TIERS[winTier],
					nameOf(win.show).getString(), luck);
		}
		if (modded) {
			ServerPlayNetworking.send(p, new Roll(c.name, reels, section, index, price, luck));
		}
		return true;
	}

	/** The speed-up button: everything this player is waiting for arrives now. */
	static void skip(ServerPlayer p) {
		for (int i = 0; i < pending.size(); i++) {
			Pending x = pending.get(i);
			if (x.player.equals(p.getUUID())) {
				pending.set(i, new Pending(x.player, x.drop, x.tier, x.caseName, 0));
			}
		}
	}

	private static int rollTier(Case c, RandomSource rand, float luck) {
		double[] weights = new double[TIERS.length];
		double total = 0;
		for (int t = 0; t < TIERS.length; t++) {
			if (c.tiers.get(t).isEmpty() || c.odds[t] <= 0) {
				continue;
			}
			double w = c.odds[t] * Math.max(0.2, 1 + luck * LUCK_BOOST[t]);
			weights[t] = w;
			total += w;
		}
		double r = rand.nextDouble() * total;
		for (int t = 0; t < TIERS.length; t++) {
			r -= weights[t];
			if (r < 0 && weights[t] > 0) {
				return t;
			}
		}
		for (int t = TIERS.length - 1; t >= 0; t--) {
			if (weights[t] > 0) {
				return t;
			}
		}
		return 0;
	}

	private static void deliverDue(MinecraftServer s, boolean all) {
		if (pending.isEmpty()) {
			return;
		}
		long now = s.getTickCount();
		Iterator<Pending> it = pending.iterator();
		while (it.hasNext()) {
			Pending p = it.next();
			if (all || now >= p.due) {
				ServerPlayer player = s.getPlayerList().getPlayer(p.player);
				if (player != null) {
					it.remove();
					deliver(player, p, true);
				}
			}
		}
	}

	private static void deliver(ServerPlayer p, Pending pend, boolean announce) {
		if (pend.drop.command != null && !pend.drop.command.isBlank()) {
			String cmd = pend.drop.command.replace("{player}", p.getGameProfile().getName());
			p.server.getCommands().performPrefixedCommand(p.server.createCommandSourceStack().withSuppressedOutput(), cmd);
		} else {
			ItemStack stack = pend.drop.show.copy();
			if (!p.getInventory().add(stack)) {
				p.drop(stack, false);
			}
			p.containerMenu.broadcastChanges();
		}
		Eidolons.recordUnbox(p, pend.tier, nameOf(pend.drop.show).getString());
		if (!announce) {
			return;
		}
		Component item = nameOf(pend.drop.show).copy().withStyle(s -> s.withColor(TIER_COLORS[pend.tier] & 0xFFFFFF));
		String count = pend.drop.show.getCount() > 1 ? " ×" + pend.drop.show.getCount() : "";
		Component cond = Condition.label(pend.drop.show);
		if (cond != null) {
			item = item.copy().append(Component.literal(" ").append(cond));
		}
		if (pend.tier >= 3 || Condition.isFactoryNew(pend.drop.show)) {
			p.server.getPlayerList().broadcastSystemMessage(Component.literal("★ ").withStyle(ChatFormatting.GOLD).append(p.getDisplayName())
					.append(Component.literal(" unboxed ").withStyle(ChatFormatting.GRAY)).append(item).append(count)
					.append(Component.literal(" (" + TIERS[pend.tier] + ") from a " + pend.caseName + "!").withStyle(ChatFormatting.GRAY)), false);
			for (ServerPlayer q : p.server.getPlayerList().getPlayers()) {
				q.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.6f, 1.2f);
			}
		} else {
			p.sendSystemMessage(Component.literal("You unboxed ").withStyle(ChatFormatting.GRAY).append(item).append(count)
					.append(Component.literal(" (" + TIERS[pend.tier] + ")").withStyle(ChatFormatting.DARK_GRAY)));
		}
	}

	// ------------------------------------------------------------------ cases file

	/** Lore lines for the shop tile: the odds and how many items each tier has. */
	static List<String> describe(String id) {
		Case c = cases.get(id);
		List<String> out = new ArrayList<>();
		if (c == null) {
			return out;
		}
		for (int t = 0; t < TIERS.length; t++) {
			out.add(String.format("%s %.2f%% (%d items)", TIERS[t], share(c.odds, t) * 100, c.tiers.get(t).size()));
		}
		return out;
	}

	/** A tier's chance, 0..1 (its weight over the total). */
	static double share(double[] odds, int t) {
		double total = 0;
		for (double o : odds) {
			total += o;
		}
		return total <= 0 ? 0 : odds[t] / total;
	}

	private static double[] parseOdds(JsonObject co) {
		double[] out = new double[TIERS.length];
		for (int t = 0; t < TIERS.length; t++) {
			out[t] = ODDS[t] / 100.0;
		}
		if (co.has("odds") && co.get("odds").isJsonArray() && co.getAsJsonArray("odds").size() == TIERS.length) {
			double[] mine = new double[TIERS.length];
			double total = 0;
			for (int t = 0; t < TIERS.length; t++) {
				mine[t] = Math.max(0, co.getAsJsonArray("odds").get(t).getAsDouble());
				total += mine[t];
			}
			if (total > 0) {
				return mine;
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ in-game case editor (ops, shop > Gamble > Edit cases)

	public static final int CASE_REQUEST = 0;
	public static final int CASE_SET_ODDS = 1;
	/** index = the inventory slot to copy into the tier. */
	public static final int CASE_ADD_SLOT = 2;
	public static final int CASE_REMOVE = 3;

	/** One case for the editor: its tiers as they are in the file (items from missing mods show as barriers). */
	public record CaseInfo(String id, String name, List<Float> odds, List<List<ItemStack>> tiers) {
	}

	public record CaseList(List<CaseInfo> cases) implements CustomPacketPayload {
		public static final Type<CaseList> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "case_list"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CaseList> CODEC = StreamCodec.ofMember((l, buf) -> {
			buf.writeVarInt(l.cases.size());
			for (CaseInfo c : l.cases) {
				buf.writeUtf(c.id, 64);
				buf.writeUtf(c.name, 64);
				for (float o : c.odds) {
					buf.writeFloat(o);
				}
				for (List<ItemStack> tier : c.tiers) {
					buf.writeVarInt(tier.size());
					for (ItemStack s : tier) {
						ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, s);
					}
				}
			}
		}, buf -> {
			int n = Math.min(buf.readVarInt(), 64);
			List<CaseInfo> out = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				String id = buf.readUtf(64);
				String name = buf.readUtf(64);
				List<Float> odds = new ArrayList<>();
				for (int t = 0; t < TIERS.length; t++) {
					odds.add(buf.readFloat());
				}
				List<List<ItemStack>> tiers = new ArrayList<>();
				for (int t = 0; t < TIERS.length; t++) {
					int m = Math.min(buf.readVarInt(), 512);
					List<ItemStack> tier = new ArrayList<>();
					for (int k = 0; k < m; k++) {
						tier.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
					}
					tiers.add(tier);
				}
				out.add(new CaseInfo(id, name, odds, tiers));
			}
			return new CaseList(out);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	public record CaseEdit(int action, String caseId, int tier, int index, List<Float> odds) implements CustomPacketPayload {
		public static final Type<CaseEdit> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "case_edit"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CaseEdit> CODEC = StreamCodec.ofMember((e, buf) -> {
			buf.writeVarInt(e.action);
			buf.writeUtf(e.caseId, 64);
			buf.writeVarInt(e.tier);
			buf.writeVarInt(e.index);
			buf.writeVarInt(e.odds.size());
			e.odds.forEach(buf::writeFloat);
		}, buf -> {
			int action = buf.readVarInt();
			String id = buf.readUtf(64);
			int tier = buf.readVarInt();
			int index = buf.readVarInt();
			int n = Math.min(buf.readVarInt(), 8);
			List<Float> odds = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				odds.add(buf.readFloat());
			}
			return new CaseEdit(action, id, tier, index, odds);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	private static JsonObject casesRoot;

	private static net.minecraft.resources.RegistryOps<JsonElement> ops() {
		return net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, server.registryAccess());
	}

	static void sendCaseList(ServerPlayer p) {
		if (casesRoot == null || !ServerPlayNetworking.canSend(p, CaseList.ID)) {
			return;
		}
		List<CaseInfo> out = new ArrayList<>();
		for (Map.Entry<String, JsonElement> ce : casesRoot.getAsJsonObject("cases").entrySet()) {
			JsonObject co = ce.getValue().getAsJsonObject();
			double[] odds = parseOdds(co);
			List<Float> f = new ArrayList<>();
			for (double o : odds) {
				f.add((float) o);
			}
			List<List<ItemStack>> tiers = new ArrayList<>();
			for (String tierName : TIERS) {
				List<ItemStack> tier = new ArrayList<>();
				if (co.has(tierName.toLowerCase())) {
					for (JsonElement de : co.getAsJsonArray(tierName.toLowerCase())) {
						Drop d = parseDrop(de.getAsJsonObject());
						if (d != null) {
							tier.add(d.show.copy());
						} else {
							ItemStack missing = new ItemStack(net.minecraft.world.item.Items.BARRIER);
							JsonObject o = de.getAsJsonObject();
							String what = o.has("item") ? o.get("item").getAsString() : o.has("show") ? o.get("show").getAsString() : "?";
							missing.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Missing mod: " + what));
							tier.add(missing);
						}
					}
				}
				tiers.add(tier);
			}
			out.add(new CaseInfo(ce.getKey(), co.has("name") ? co.get("name").getAsString() : ce.getKey(), f, tiers));
		}
		ServerPlayNetworking.send(p, new CaseList(out));
	}

	private static void caseFail(ServerPlayer p, String why) {
		p.displayClientMessage(Component.literal(why).withStyle(ChatFormatting.RED), true);
		sendCaseList(p);
	}

	static void editCase(ServerPlayer p, CaseEdit e) {
		if (!p.hasPermissions(2) || casesRoot == null) {
			return;
		}
		if (e.action == CASE_REQUEST) {
			sendCaseList(p);
			return;
		}
		JsonObject all = casesRoot.getAsJsonObject("cases");
		if (!all.has(e.caseId)) {
			caseFail(p, "That case doesn't exist any more.");
			return;
		}
		JsonObject co = all.getAsJsonObject(e.caseId);
		String name = co.has("name") ? co.get("name").getAsString() : e.caseId;
		String what;
		switch (e.action) {
			case CASE_SET_ODDS -> {
				if (e.odds.size() != TIERS.length) {
					return;
				}
				com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
				double total = 0;
				for (float o : e.odds) {
					double v = Math.max(0, Math.min(100000, Math.round(o * 10000.0) / 10000.0));
					arr.add(v);
					total += v;
				}
				if (total <= 0) {
					caseFail(p, "At least one tier needs odds above 0.");
					return;
				}
				co.add("odds", arr);
				what = "set the odds";
			}
			case CASE_ADD_SLOT -> {
				if (e.tier < 0 || e.tier >= TIERS.length || e.index < 0 || e.index >= p.getInventory().getContainerSize()) {
					return;
				}
				ItemStack stack = p.getInventory().getItem(e.index);
				if (stack.isEmpty()) {
					caseFail(p, "That slot is empty.");
					return;
				}
				JsonObject drop = new JsonObject();
				if (stack.getComponentsPatch().isEmpty()) {
					drop.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
					if (stack.getCount() > 1) {
						drop.addProperty("count", stack.getCount());
					}
				} else {
					var json = ItemStack.CODEC.encodeStart(ops(), stack.copy()).result();
					if (json.isEmpty()) {
						caseFail(p, "That item can't be saved.");
						return;
					}
					drop.addProperty("show", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
					drop.add("stack", json.get());
				}
				String key = TIERS[e.tier].toLowerCase();
				if (!co.has(key)) {
					co.add(key, new com.google.gson.JsonArray());
				}
				co.getAsJsonArray(key).add(drop);
				what = "added " + stack.getCount() + "x " + stack.getHoverName().getString() + " to " + TIERS[e.tier];
			}
			case CASE_REMOVE -> {
				String key = TIERS[e.tier < 0 || e.tier >= TIERS.length ? 0 : e.tier].toLowerCase();
				com.google.gson.JsonArray arr = co.has(key) ? co.getAsJsonArray(key) : null;
				if (arr == null || e.index < 0 || e.index >= arr.size()) {
					caseFail(p, "That item is already gone.");
					return;
				}
				int total = 0;
				for (String t : TIERS) {
					total += co.has(t.toLowerCase()) ? co.getAsJsonArray(t.toLowerCase()).size() : 0;
				}
				if (total <= 1) {
					caseFail(p, "A case needs at least one item.");
					return;
				}
				arr.remove(e.index);
				what = "removed an item from " + TIERS[e.tier];
			}
			default -> {
				return;
			}
		}
		try {
			Files.writeString(FMLPaths.CONFIGDIR.get().resolve("bubble-cases.json"), GSON.toJson(casesRoot));
		} catch (Exception ex) {
			Bubble.LOG.error("Could not save config/bubble-cases.json", ex);
		}
		load();
		String who = p.getGameProfile().getName();
		Bubble.LOG.info("Cases: {} {} ({})", who, what, e.caseId);
		for (ServerPlayer q : p.server.getPlayerList().getPlayers()) {
			if (q.hasPermissions(2)) {
				q.sendSystemMessage(Component.literal("[Cases] " + who + " " + what + " in the " + name).withStyle(ChatFormatting.GRAY));
				sendCaseList(q);
			}
		}
	}

	static void load() {
		cases.clear();
		var file = FMLPaths.CONFIGDIR.get().resolve("bubble-cases.json");
		try {
			JsonObject root;
			if (Files.exists(file)) {
				root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
				boolean changed = addNewDefaults(root);
				changed |= migrate(root);
				if (changed) {
					Files.writeString(file, GSON.toJson(root));
				}
			} else {
				root = JsonParser.parseString(DEFAULTS).getAsJsonObject();
				migrate(root);
				Files.createDirectories(file.getParent());
				Files.writeString(file, GSON.toJson(root));
			}
			casesRoot = root;
			int missing = 0;
			for (Map.Entry<String, JsonElement> ce : root.getAsJsonObject("cases").entrySet()) {
				JsonObject co = ce.getValue().getAsJsonObject();
				List<List<Drop>> tiers = new ArrayList<>();
				for (String tierName : TIERS) {
					List<Drop> pool = new ArrayList<>();
					if (co.has(tierName.toLowerCase())) {
						for (JsonElement de : co.getAsJsonArray(tierName.toLowerCase())) {
							Drop d = parseDrop(de.getAsJsonObject());
							if (d == null) {
								missing++;
							} else {
								pool.add(d);
							}
						}
					}
					tiers.add(pool);
				}
				if (tiers.stream().allMatch(List::isEmpty)) {
					continue;
				}
				cases.put(ce.getKey(), new Case(ce.getKey(), co.has("name") ? co.get("name").getAsString() : ce.getKey(), tiers, parseColor(co, ce.getKey()), parseOdds(co)));
			}
			Bubble.LOG.info("Cases: {} loaded ({} items left out because their mods aren't installed)", cases.size(), missing);
			for (Case c : cases.values()) {
				double ev = 0;
				for (int t = 0; t < TIERS.length; t++) {
					List<Drop> pool = c.tiers.get(t);
					if (!pool.isEmpty()) {
						ev += share(c.odds, t) * pool.stream().mapToDouble(d -> d.command == null ? Matter.value(d.show) : 0).average().orElse(0);
					}
				}
				Bubble.LOG.info("Cases: {} sells back for about {} Strange Matter on average", c.name, Math.round(ev));
			}
		} catch (Exception e) {
			Bubble.LOG.error("Could not read config/bubble-cases.json", e);
		}
	}

	/**
	 * /bubble odds case <case> [opens]: opens it that many times for nobody (a stand-in player, no charge, nothing
	 * given) and counts the tiers and how many prizes came out Enchanted, Apothic or Factory New.
	 */
	static String simulate(MinecraftServer s, String id, int opens) {
		Case c = cases.get(id);
		if (c == null) {
			return "No case called '" + id + "'. Cases: " + String.join(", ", cases.keySet());
		}
		ServerPlayer fake = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(s.overworld());
		RandomSource rand = s.overworld().random;
		int[] tiers = new int[TIERS.length];
		Map<String, Integer> conds = new LinkedHashMap<>();
		int gear = 0;
		for (int i = 0; i < opens; i++) {
			int tier = rollTier(c, rand, 0);
			List<Drop> pool = c.tiers.get(tier);
			Drop d = pool.get(rand.nextInt(pool.size()));
			tiers[tier]++;
			if ((d.command == null || d.command.isBlank()) && d.show.getCount() == 1 && (d.show.isEnchantable() || d.show.isDamageableItem())) {
				gear++;
			}
			Drop out = Condition.roll(fake, d, rand);
			Component l = Condition.label(out.show);
			if (l != null) {
				conds.merge(l.getString(), 1, Integer::sum);
			}
		}
		StringBuilder sb = new StringBuilder(opens + " " + c.name + "s:");
		for (int t = 0; t < TIERS.length; t++) {
			sb.append(' ').append(TIERS[t]).append(' ').append(tiers[t]);
		}
		sb.append(" | gear prizes ").append(gear).append(", better: ").append(conds.isEmpty() ? "none" : conds.toString());
		return sb.toString();
	}

	/** The cases file's "conditions" (Condition), or null. */
	static JsonObject conditions() {
		JsonObject root = casesRoot;
		return root != null && root.has("conditions") && root.get("conditions").isJsonObject() ? root.getAsJsonObject("conditions") : null;
	}

	/** The cases file's "mobDrops" (MobDrops), or null. */
	static JsonObject mobDrops() {
		JsonObject root = casesRoot;
		return root != null && root.has("mobDrops") && root.get("mobDrops").isJsonObject() ? root.getAsJsonObject("mobDrops") : null;
	}

	/** A chance from the file's "extras" (chests, mobs), or `def` if it isn't there. */
	static double extra(String key, double def) {
		JsonObject root = casesRoot;
		if (root == null || !root.has("extras")) {
			return def;
		}
		JsonObject ex = root.getAsJsonObject("extras");
		return ex.has(key) ? ex.get(key).getAsDouble() : def;
	}

	private static void addTo(JsonObject cs, String caseId, String tier, JsonObject drop) {
		if (cs.has(caseId)) {
			JsonObject co = cs.getAsJsonObject(caseId);
			if (!co.has(tier)) {
				co.add(tier, new com.google.gson.JsonArray());
			}
			co.getAsJsonArray(tier).add(drop);
		}
	}

	private static JsonObject item(String id, int count) {
		JsonObject o = new JsonObject();
		o.addProperty("item", id);
		if (count > 1) {
			o.addProperty("count", count);
		}
		return o;
	}

	/** One-off additions to cases files (each runs once, also on a fresh file; markers in "migrations"). */
	private static boolean migrate(JsonObject root) {
		com.google.gson.JsonArray done = root.has("migrations") ? root.getAsJsonArray("migrations") : new com.google.gson.JsonArray();
		root.add("migrations", done);
		boolean changed = false;
		JsonObject cs = root.getAsJsonObject("cases");
		// 1.14.0: Wheel Spins, guns (TACZ) and Estrogen items; chest and mob chances
		if (!done.contains(new com.google.gson.JsonPrimitive("wheel_guns_estrogen_v1"))) {
			done.add("wheel_guns_estrogen_v1");
			addTo(cs, "mod", "rare", item("bubble:wheel_spin", 1));
			addTo(cs, "mod", "epic", item("bubble:wheel_spin", 2));
			addTo(cs, "progression", "rare", item("bubble:wheel_spin", 1));
			addTo(cs, "twilight", "rare", item("bubble:wheel_spin", 1));
			addTo(cs, "weapons", "epic", item("bubble:wheel_spin", 1));
			for (String gun : new String[] {"ak47", "deagle_golden"}) {
				addTo(cs, "mod", "legendary", Guns.caseDrop(gun));
			}
			for (String gun : new String[] {"ak47", "m249", "ai_awp", "spas_12", "p90", "deagle"}) {
				addTo(cs, "weapons", "legendary", Guns.caseDrop(gun));
			}
			addTo(cs, "mod", "uncommon", item("estrogen:estrogen_pill", 3));
			addTo(cs, "mod", "uncommon", item("estrogen:estrogen_chip_cookie", 6));
			addTo(cs, "mod", "rare", item("estrogen:estrogen_patch", 1));
			addTo(cs, "mod", "rare", item("estrogen:thigh_highs", 1));
			addTo(cs, "mod", "epic", item("estrogen:moth_elytra", 1));
			addTo(cs, "mod", "uncommon", item("waterframes:frame", 2));
			addTo(cs, "mod", "rare", item("waterframes:tv", 1));
			if (!root.has("extras")) {
				JsonObject ex = new JsonObject();
				ex.addProperty("chestWheelSpin", 0.03);
				ex.addProperty("chestGun", 0.006);
				ex.addProperty("mobGunChance", 0.002);
				root.add("extras", ex);
			}
			Bubble.LOG.info("Cases: added Wheel Spins, guns and Estrogen items to the cases");
			changed = true;
		}
		// 1.19.0: The Shooting Star's Stellar Remote, a Legendary in the Mod Case (about 1 in 940 cases with the server's odds and no luck)
		if (!done.contains(new com.google.gson.JsonPrimitive("stellar_remote_v1"))) {
			done.add("stellar_remote_v1");
			addTo(cs, "mod", "legendary", item("shooting_star_demo:stellar_remote", 1));
			Bubble.LOG.info("Cases: added the Stellar Remote to the Mod Case (Legendary)");
			changed = true;
		}
		// 1.22.0: cases, spins and Strange Matter from mobs
		if (!done.contains(new com.google.gson.JsonPrimitive("mob_drops_v1"))) {
			done.add("mob_drops_v1");
			if (!root.has("mobDrops")) {
				root.add("mobDrops", MobDrops.defaults());
			}
			Bubble.LOG.info("Cases: added mob drops (cases, spins and Strange Matter from mobs)");
			changed = true;
		}
		// 1.22.0: gear prizes can come enchanted, Apothic or Factory New (both)
		if (!done.contains(new com.google.gson.JsonPrimitive("conditions_v1"))) {
			done.add("conditions_v1");
			if (!root.has("conditions")) {
				root.add("conditions", Condition.defaults());
			}
			changed = true;
		}
		return changed;
	}

	/** Cases added in a newer version get added to an existing file once (a deleted case stays deleted). */
	private static boolean addNewDefaults(JsonObject root) {
		JsonObject defaults = JsonParser.parseString(DEFAULTS).getAsJsonObject().getAsJsonObject("cases");
		JsonObject cs = root.getAsJsonObject("cases");
		com.google.gson.JsonArray seen = root.has("defaultsSeen") ? root.getAsJsonArray("defaultsSeen") : new com.google.gson.JsonArray();
		if (!root.has("defaultsSeen")) {
			cs.keySet().forEach(seen::add);
			root.add("defaultsSeen", seen);
		}
		boolean changed = false;
		for (String id : defaults.keySet()) {
			if (!seen.contains(new com.google.gson.JsonPrimitive(id))) {
				seen.add(id);
				if (!cs.has(id)) {
					cs.add(id, defaults.get(id));
					Bubble.LOG.info("Cases: added the new '{}' case to config/bubble-cases.json", id);
				}
				changed = true;
			}
		}
		return changed;
	}

	private static Drop parseDrop(JsonObject o) {
		if (o.has("stack") && server != null) {
			ItemStack full = ItemStack.CODEC.parse(ops(), o.get("stack")).result().orElse(null);
			if (full == null) {
				return null;
			}
			return new Drop(full, o.has("command") ? o.get("command").getAsString() : null);
		}
		String id = o.has("item") ? o.get("item").getAsString() : o.has("show") ? o.get("show").getAsString() : null;
		if (id == null) {
			return null;
		}
		Optional<Item> item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(id));
		if (item.isEmpty()) {
			return null;
		}
		ItemStack stack = new ItemStack(item.get(), o.has("count") ? Math.max(1, o.get("count").getAsInt()) : 1);
		String command = o.has("command") ? o.get("command").getAsString() : null;
		if (o.has("name")) {
			stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
					Component.literal(o.get("name").getAsString()).withStyle(s -> s.withItalic(false)));
		}
		if (command != null && command.startsWith("bubble apothloot") && !net.neoforged.fml.ModList.get().isLoaded("apotheosis")) {
			return null;
		}
		return new Drop(stack, command);
	}

	private static final String DEFAULTS = """
			{
			  "cases": {
			    "mod": {
			      "name": "Mod Case",
			      "common": [
			        {"item": "minecraft:iron_ingot", "count": 16}, {"item": "minecraft:gold_ingot", "count": 8},
			        {"item": "minecraft:redstone", "count": 32}, {"item": "minecraft:lapis_lazuli", "count": 24},
			        {"item": "minecraft:coal_block", "count": 8}, {"item": "minecraft:cooked_beef", "count": 32},
			        {"item": "minecraft:golden_carrot", "count": 16}, {"item": "minecraft:arrow", "count": 64},
			        {"item": "minecraft:torch", "count": 64}, {"item": "minecraft:experience_bottle", "count": 8},
			        {"item": "twilightforest:ironwood_ingot", "count": 4}, {"item": "twilightforest:torchberries", "count": 16},
			        {"item": "oritech:nickel_ingot", "count": 8}, {"item": "aether:ambrosium_shard", "count": 12},
			        {"item": "apotheosis:mysterious_scrap_metal", "count": 8}, {"item": "apotheosis:gem_dust", "count": 16},
			        {"item": "sophisticatedbackpacks:upgrade_base", "count": 2}, {"item": "waystones:warp_dust", "count": 8}
			      ],
			      "uncommon": [
			        {"item": "minecraft:diamond", "count": 3}, {"item": "minecraft:emerald", "count": 6},
			        {"item": "minecraft:ender_pearl", "count": 2}, {"item": "minecraft:golden_apple", "count": 2},
			        {"item": "minecraft:obsidian", "count": 10}, {"item": "twilightforest:steeleaf_ingot", "count": 6},
			        {"item": "twilightforest:naga_scale", "count": 4}, {"item": "aether:zanite_gemstone", "count": 6},
			        {"item": "apotheosis:timeworn_fabric", "count": 4}, {"item": "apotheosis:sigil_of_socketing"},
			        {"item": "waystones:waystone"}, {"item": "sophisticatedbackpacks:iron_backpack"},
			        {"item": "simplyswords:iron_longsword"}, {"item": "simplyswords:iron_katana"},
			        {"item": "naturescompass:naturescompass"}, {"item": "explorerscompass:explorerscompass"},
			        {"item": "cataclysm:koboleton_bone", "count": 6}, {"item": "lucky:lucky_block", "count": 2}
			      ],
			      "rare": [
			        {"item": "minecraft:ender_eye", "count": 2}, {"item": "minecraft:blaze_rod", "count": 4},
			        {"item": "minecraft:diamond", "count": 8}, {"item": "minecraft:diamond_chestplate"},
			        {"item": "minecraft:trident"}, {"item": "twilightforest:charm_of_life_1"}, {"item": "twilightforest:fiery_ingot", "count": 2},
			        {"item": "apotheosis:luminous_crystal_shard", "count": 2}, {"item": "simplyswords:diamond_longsword"},
			        {"item": "simplyswords:diamond_scythe"}, {"item": "sophisticatedbackpacks:gold_backpack"},
			        {"item": "artifacts:running_shoes"}, {"item": "artifacts:cloud_in_a_bottle"}, {"item": "artifacts:lucky_scarf"},
			        {"item": "aether:enchanted_gravitite", "count": 2}, {"item": "oritech:adamant_ingot", "count": 2},
			        {"show": "minecraft:iron_sword", "name": "Random Rare Apotheosis item", "command": "bubble apothloot {player} rare"}
			      ],
			      "epic": [
			        {"item": "minecraft:netherite_ingot"}, {"item": "minecraft:totem_of_undying"}, {"item": "minecraft:ender_eye", "count": 6},
			        {"item": "minecraft:enchanted_golden_apple"}, {"item": "artifacts:crystal_heart"}, {"item": "artifacts:power_glove"},
			        {"item": "simplyswords:emberblade"}, {"item": "simplyswords:frostfall"}, {"item": "twilightforest:charm_of_keeping_2"},
			        {"item": "apotheosis:arcane_sands"}, {"item": "immersive_aircraft:biplane"}, {"item": "cataclysm:void_eye"},
			        {"show": "minecraft:diamond_sword", "name": "Random Epic Apotheosis item", "command": "bubble apothloot {player} epic"}
			      ],
			      "legendary": [
			        {"item": "minecraft:elytra"}, {"item": "minecraft:nether_star"}, {"item": "minecraft:beacon"},
			        {"item": "apotheosis:godforged_pearl"}, {"item": "twilightforest:lamp_of_cinders"}, {"item": "simplyswords:stormbringer"},
			        {"show": "minecraft:netherite_sword", "name": "Random Mythic Apotheosis item", "command": "bubble apothloot {player} mythic"}
			      ]
			    },
			    "progression": {
			      "name": "Progression Case",
			      "common": [
			        {"item": "minecraft:iron_ingot", "count": 24}, {"item": "minecraft:obsidian", "count": 6},
			        {"item": "minecraft:gold_ingot", "count": 12}, {"item": "minecraft:flint_and_steel"},
			        {"item": "minecraft:string", "count": 16}, {"item": "minecraft:leather", "count": 12},
			        {"item": "minecraft:book", "count": 12}, {"item": "minecraft:glowstone_dust", "count": 16},
			        {"item": "minecraft:nether_wart", "count": 16}, {"item": "minecraft:quartz", "count": 24}
			      ],
			      "uncommon": [
			        {"item": "minecraft:ender_pearl", "count": 3}, {"item": "minecraft:diamond", "count": 4},
			        {"item": "minecraft:blaze_powder", "count": 4}, {"item": "minecraft:ghast_tear", "count": 2},
			        {"item": "minecraft:enchanting_table"}, {"item": "minecraft:crying_obsidian", "count": 6},
			        {"item": "minecraft:diamond_pickaxe"}, {"item": "minecraft:shulker_shell"}
			      ],
			      "rare": [
			        {"item": "minecraft:ender_eye", "count": 3}, {"item": "minecraft:blaze_rod", "count": 6},
			        {"item": "minecraft:ancient_debris", "count": 2}, {"item": "minecraft:diamond", "count": 12},
			        {"item": "minecraft:wither_skeleton_skull"}, {"item": "minecraft:shulker_shell", "count": 4}
			      ],
			      "epic": [
			        {"item": "minecraft:ender_eye", "count": 8}, {"item": "minecraft:netherite_ingot", "count": 2},
			        {"item": "minecraft:wither_skeleton_skull", "count": 3}, {"item": "minecraft:totem_of_undying"},
			        {"item": "minecraft:elytra"}
			      ],
			      "legendary": [
			        {"item": "minecraft:ender_eye", "count": 12}, {"item": "minecraft:nether_star"}, {"item": "minecraft:dragon_head"},
			        {"item": "minecraft:netherite_ingot", "count": 4}
			      ]
			    },
			    "twilight": {
			      "name": "Twilight Forest Case",
			      "common": [
			        {"item": "twilightforest:ironwood_ingot", "count": 4}, {"item": "twilightforest:torchberries", "count": 16},
			        {"item": "twilightforest:raven_feather", "count": 6}, {"item": "twilightforest:liveroot", "count": 8},
			        {"item": "twilightforest:steeleaf_ingot", "count": 3}, {"item": "twilightforest:arctic_fur", "count": 3},
			        {"item": "twilightforest:cooked_meef", "count": 8}, {"item": "twilightforest:firefly", "count": 4},
			        {"item": "twilightforest:moonworm", "count": 4}, {"item": "twilightforest:naga_scale", "count": 2},
			        {"item": "twilightforest:cicada", "count": 3}, {"item": "twilightforest:meef_stroganoff", "count": 2}
			      ],
			      "uncommon": [
			        {"item": "twilightforest:naga_scale", "count": 6}, {"item": "twilightforest:steeleaf_ingot", "count": 8},
			        {"item": "twilightforest:ironwood_ingot", "count": 12}, {"item": "twilightforest:knightmetal_ingot", "count": 3},
			        {"item": "twilightforest:carminite", "count": 2}, {"item": "twilightforest:magic_map_focus"},
			        {"item": "twilightforest:transformation_powder", "count": 4}, {"item": "twilightforest:ironwood_sword"},
			        {"item": "twilightforest:steeleaf_pickaxe"}, {"item": "twilightforest:naga_chestplate"},
			        {"item": "twilightforest:arctic_chestplate"}, {"item": "twilightforest:maze_map_focus"}
			      ],
			      "rare": [
			        {"item": "twilightforest:fiery_ingot", "count": 2}, {"item": "twilightforest:charm_of_life_1"},
			        {"item": "twilightforest:charm_of_keeping_1"}, {"item": "twilightforest:ore_magnet"},
			        {"item": "twilightforest:knightmetal_sword"}, {"item": "twilightforest:ice_bow"}, {"item": "twilightforest:triple_bow"},
			        {"item": "twilightforest:seeker_bow"}, {"item": "twilightforest:peacock_feather_fan"},
			        {"item": "twilightforest:moonworm_queen"}, {"item": "twilightforest:uncrafting_table"},
			        {"item": "twilightforest:fiery_blood", "count": 2}
			      ],
			      "epic": [
			        {"item": "twilightforest:charm_of_life_2"}, {"item": "twilightforest:charm_of_keeping_2"},
			        {"item": "twilightforest:fiery_sword"}, {"item": "twilightforest:fiery_pickaxe"}, {"item": "twilightforest:ender_bow"},
			        {"item": "twilightforest:giant_sword"}, {"item": "twilightforest:giant_pickaxe"}, {"item": "twilightforest:block_and_chain"},
			        {"item": "twilightforest:mazebreaker_pickaxe"}, {"item": "twilightforest:phantom_helmet"},
			        {"item": "twilightforest:alpha_yeti_fur", "count": 2}, {"item": "twilightforest:hydra_chop", "count": 2}
			      ],
			      "legendary": [
			        {"item": "twilightforest:lamp_of_cinders"}, {"item": "twilightforest:cube_of_annihilation"},
			        {"item": "twilightforest:charm_of_keeping_3"}, {"item": "twilightforest:twilight_scepter"},
			        {"item": "twilightforest:lifedrain_scepter"}, {"item": "twilightforest:fortification_scepter"},
			        {"item": "twilightforest:zombie_scepter"}, {"item": "twilightforest:hydra_trophy"},
			        {"item": "twilightforest:ur_ghast_trophy"}
			      ]
			    },
			    "weapons": {
			      "name": "Weapon Case",
			      "common": [
			        {"item": "simplyswords:iron_longsword"}, {"item": "simplyswords:iron_katana"}, {"item": "simplyswords:iron_rapier"},
			        {"item": "simplyswords:iron_sai"}, {"item": "simplyswords:iron_spear"}, {"item": "simplyswords:iron_glaive"},
			        {"item": "minecraft:crossbow"}, {"item": "minecraft:bow"}, {"item": "minecraft:arrow", "count": 64}, {"item": "minecraft:shield"}
			      ],
			      "uncommon": [
			        {"item": "simplyswords:diamond_longsword"}, {"item": "simplyswords:diamond_katana"}, {"item": "simplyswords:diamond_greataxe"},
			        {"item": "simplyswords:diamond_scythe"}, {"item": "simplybows:vine_bow/vine_bow"}, {"item": "simplybows:ice_bow/ice_bow"},
			        {"item": "twilightforest:ironwood_sword"}, {"item": "aether:zanite_sword"},
			        {"show": "minecraft:iron_sword", "name": "Random Apotheosis item", "command": "bubble apothloot {player} uncommon"}
			      ],
			      "rare": [
			        {"item": "simplyswords:emberlash"}, {"item": "simplyswords:hearthflame"}, {"item": "simplyswords:twisted_blade"},
			        {"item": "simplybows:echo_bow/echo_bow"}, {"item": "simplybows:bee_bow/bee_bow"}, {"item": "twilightforest:fiery_sword"},
			        {"item": "twilightforest:knightmetal_sword"}, {"item": "aether:gravitite_sword"}, {"item": "oritech:electric_mace"},
			        {"show": "minecraft:iron_sword", "name": "Random Rare Apotheosis item", "command": "bubble apothloot {player} rare"}
			      ],
			      "epic": [
			        {"item": "simplyswords:emberblade"}, {"item": "simplyswords:frostfall"}, {"item": "simplyswords:soulrender"},
			        {"item": "simplybows:cosmic_bow/cosmic_bow"}, {"item": "twilightforest:giant_sword"}, {"item": "aether:flaming_sword"},
			        {"show": "minecraft:diamond_sword", "name": "Random Epic Apotheosis item", "command": "bubble apothloot {player} epic"}
			      ],
			      "legendary": [
			        {"item": "simplyswords:stormbringer"}, {"item": "simplyswords:awakened_lichblade"}, {"item": "simplyswords:the_devourer"},
			        {"item": "minecraft:mace"},
			        {"show": "minecraft:netherite_sword", "name": "Random Mythic Apotheosis item", "command": "bubble apothloot {player} mythic"}
			      ]
			    }
			  }
			}""";
}
