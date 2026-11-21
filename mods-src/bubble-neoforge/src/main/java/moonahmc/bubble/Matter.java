package moonahmc.bubble;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import moonahmc.bubble.net.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Strange Matter: everyone's wallet, and what every item is worth.
 * <p>
 * Raw materials have hand-picked values (diamonds, emeralds, ender pearls... are worth a lot). Everything else is
 * worked out from its recipes: an item is worth what its ingredients are worth, plus a bonus for every crafting step
 * that combines different things, so deep crafting trees pay more. Enchantments add value; damage takes it away.
 */
public final class Matter {
	/** Crafting something out of two or more different ingredients adds this much on top. */
	private static final double CRAFT_BONUS = 1.08;
	private static final int UNDO_DEPTH = 9;

	private static final Map<Item, Double> values = new IdentityHashMap<>();
	private static final Map<UUID, ArrayDeque<Sale>> lastSales = new HashMap<>();
	private static MinecraftServer server;

	record Sale(ItemStack stack, double paid) {
	}

	private Matter() {
	}

	// ------------------------------------------------------------------ wallets (saved with the world, so each run starts at 0)

	/** Balances live in the world folder (bubble-matter.json), so every new world starts everyone at 0. */
	private static final Map<UUID, Double> wallets = new HashMap<>();
	private static java.nio.file.Path walletFile;

	private static void loadWallets() {
		wallets.clear();
		try {
			if (java.nio.file.Files.exists(walletFile)) {
				com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(walletFile)).getAsJsonObject();
				for (var e : o.entrySet()) {
					try {
						wallets.put(UUID.fromString(e.getKey()), e.getValue().getAsDouble());
					} catch (IllegalArgumentException ignored) {
					}
				}
			}
		} catch (Exception e) {
			Bubble.LOG.error("Could not read {}", walletFile, e);
		}
	}

	private static void saveWallets() {
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		wallets.forEach((id, v) -> o.addProperty(id.toString(), v));
		try {
			java.nio.file.Files.writeString(walletFile, o.toString());
		} catch (Exception e) {
			Bubble.LOG.error("Could not save {}", walletFile, e);
		}
	}

	public static double balance(ServerPlayer p) {
		return server == null ? 0 : wallets.getOrDefault(p.getUUID(), 0.0);
	}

	/** Adds (or with a negative amount, takes) matter. Returns false if they can't afford it. */
	public static boolean add(ServerPlayer p, double amount) {
		double now = wallets.getOrDefault(p.getUUID(), 0.0);
		if (now + amount < -1e-9) {
			return false;
		}
		wallets.put(p.getUUID(), Math.max(0, now + amount));
		saveWallets();
		sendBalance(p);
		return true;
	}

	/** Matter earned from playing (goals, kills, the wheel), doubled while the wheel's "Double Matter" is on. Not for selling. */
	public static void earn(ServerPlayer p, double amount) {
		add(p, amount > 0 && WheelStatus.doubleMatter() ? amount * 2 : amount);
	}

	public static void sendBalance(ServerPlayer p) {
		if (ServerPlayNetworking.canSend(p, ShopPayloads.Balance.ID)) {
			ServerPlayNetworking.send(p, new ShopPayloads.Balance(balance(p)));
		}
	}

	static void onServerStarted(MinecraftServer s) {
		server = s;
		lastSales.clear();
		walletFile = s.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("bubble-matter.json");
		loadWallets();
		long t = System.currentTimeMillis();
		computeValues(s);
		Bubble.LOG.info("Strange Matter: priced {} items in {} ms", values.size(), System.currentTimeMillis() - t);
	}

	static void onServerStopped() {
		server = null;
		values.clear();
		wallets.clear();
	}

	// ------------------------------------------------------------------ selling

	/** Items that can't be sold at all (they stay where they are). The Stellar Remote is a super-rare case prize whose
	 *  crafting tree would make it worth tens of thousands of Strange Matter. */
	private static final java.util.Set<String> UNSELLABLE = java.util.Set.of("shooting_star_demo:stellar_remote", "shooting_star_demo:stellar_core");

	/** This stack can't be sold: it's on the list, or it's a shulker box / bundle with something on the list inside. */
	public static boolean unsellable(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		if (UNSELLABLE.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
			return true;
		}
		ItemContainerContents container = stack.get(DataComponents.CONTAINER);
		if (container != null) {
			for (ItemStack inner : container.nonEmptyItems()) {
				if (unsellable(inner)) {
					return true;
				}
			}
		}
		BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
		if (bundle != null) {
			for (ItemStack inner : bundle.items()) {
				if (unsellable(inner)) {
					return true;
				}
			}
		}
		return false;
	}

	private static void refuse(ServerPlayer p, ItemStack stack) {
		boolean itself = UNSELLABLE.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		p.displayClientMessage(Component.empty().append(stack.getHoverName()).append(Component.literal(" can't be sold").withStyle(ChatFormatting.RED))
				.append(Component.literal(itself ? "" : " (there's something inside that can't be)").withStyle(ChatFormatting.GRAY)), true);
		p.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.7f, 1f);
	}

	static void sell(ServerPlayer p, boolean all) {
		AbstractContainerMenu h = p.containerMenu;
		ItemStack cursor = h.getCarried();
		if (cursor.isEmpty()) {
			return;
		}
		if (unsellable(cursor)) {
			refuse(p, cursor);
			return;
		}
		ItemStack sold = cursor.copy();
		double bonus = Eidolons.sellBonus(p);
		double total = value(cursor) * bonus;
		h.setCarried(ItemStack.EMPTY);
		push(p, new Sale(sold, total));
		int count = sold.getCount();
		if (all) {
			var inv = p.getInventory();
			for (int i = 0; i < inv.items.size(); i++) {
				ItemStack s = inv.items.get(i);
				if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, sold) && !unsellable(s)) {
					double v = value(s) * bonus;
					total += v;
					count += s.getCount();
					push(p, new Sale(s.copy(), v));
					inv.items.set(i, ItemStack.EMPTY);
				}
			}
		}
		add(p, total);
		h.broadcastChanges();
		p.displayClientMessage(Component.literal("Sold " + count + "× ").withStyle(ChatFormatting.GRAY).append(sold.getHoverName())
				.append(Component.literal(" for " + format(total) + " Strange Matter").withStyle(ChatFormatting.AQUA)), true);
		p.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8f, 1.4f);
		sendQuote(p, ItemStack.EMPTY);
	}

	/** Ctrl+click: sell the stack in one of your own inventory slots straight away. */
	static void quickSell(ServerPlayer p, int slotId) {
		AbstractContainerMenu h = p.containerMenu;
		if (slotId < 0 || slotId >= h.slots.size()) {
			return;
		}
		net.minecraft.world.inventory.Slot slot = h.getSlot(slotId);
		if (slot.container != p.getInventory() || !slot.hasItem() || !slot.mayPickup(p)) {
			return; // only your own inventory (not chests), and not cursed armour
		}
		ItemStack sold = slot.getItem().copy();
		if (unsellable(sold)) {
			refuse(p, sold);
			return;
		}
		double total = value(sold) * Eidolons.sellBonus(p);
		slot.setByPlayer(ItemStack.EMPTY);
		push(p, new Sale(sold, total));
		add(p, total);
		h.broadcastChanges();
		p.displayClientMessage(Component.literal("Sold " + sold.getCount() + "× ").withStyle(ChatFormatting.GRAY).append(sold.getHoverName())
				.append(Component.literal(" for " + format(total) + " Strange Matter").withStyle(ChatFormatting.AQUA))
				.append(Component.literal("  (right-click the sell slot to undo)").withStyle(ChatFormatting.DARK_GRAY)), true);
		p.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8f, 1.4f);
		sendQuote(p, ItemStack.EMPTY);
	}

	private static void push(ServerPlayer p, Sale sale) {
		ArrayDeque<Sale> d = lastSales.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
		d.addFirst(sale);
		while (d.size() > UNDO_DEPTH) {
			d.removeLast();
		}
	}

	/** Undo the most recent sale: pay back what you got and take the item back. */
	static void buyBack(ServerPlayer p) {
		ArrayDeque<Sale> d = lastSales.get(p.getUUID());
		if (d == null || d.isEmpty()) {
			p.displayClientMessage(Component.literal("Nothing to buy back").withStyle(ChatFormatting.GRAY), true);
			return;
		}
		Sale sale = d.peekFirst();
		if (!add(p, -sale.paid())) {
			p.displayClientMessage(Component.literal("You need " + format(sale.paid()) + " Strange Matter to buy that back").withStyle(ChatFormatting.RED), true);
			return;
		}
		d.removeFirst();
		AbstractContainerMenu h = p.containerMenu;
		if (h.getCarried().isEmpty()) {
			h.setCarried(sale.stack().copy());
		} else {
			p.getInventory().placeItemBackInInventory(sale.stack().copy());
		}
		h.broadcastChanges();
		p.displayClientMessage(Component.literal("Bought back ").withStyle(ChatFormatting.GRAY).append(sale.stack().getHoverName()), true);
		sendQuote(p, ItemStack.EMPTY);
	}

	/** For the "Sells for" line on item tooltips: what each stack would sell for in the sell slot right now. */
	static void sendWorth(ServerPlayer p, java.util.List<ItemStack> stacks) {
		if (!ServerPlayNetworking.canSend(p, ShopPayloads.WorthResult.ID) || stacks.isEmpty()) {
			return;
		}
		double bonus = Eidolons.sellBonus(p);
		java.util.List<ItemStack> out = new java.util.ArrayList<>();
		java.util.List<Double> values = new java.util.ArrayList<>();
		for (ItemStack s : stacks) {
			out.add(s.copy());
			values.add(s.isEmpty() ? 0 : unsellable(s) ? -1 : value(s) * bonus);
		}
		ServerPlayNetworking.send(p, new ShopPayloads.WorthResult(out, values));
	}

	static void sendQuote(ServerPlayer p, ItemStack stack) {
		if (!ServerPlayNetworking.canSend(p, ShopPayloads.QuoteResult.ID)) {
			return;
		}
		ArrayDeque<Sale> d = lastSales.get(p.getUUID());
		Sale last = d == null ? null : d.peekFirst();
		ServerPlayNetworking.send(p, new ShopPayloads.QuoteResult(stack.copy(), stack.isEmpty() ? 0 : unsellable(stack) ? -1 : value(stack),
				last == null ? ItemStack.EMPTY : last.stack().copy(), last == null ? 0 : last.paid()));
	}

	public static String format(double v) {
		if (v >= 100 || Math.abs(v - Math.rint(v)) < 0.05) {
			return String.format("%,d", (long) Math.floor(v + 1e-6));
		}
		return String.format("%.1f", v);
	}

	// ------------------------------------------------------------------ values

	/** What a whole stack sells for. */
	public static double value(ItemStack stack) {
		if (stack.isEmpty() || UNSELLABLE.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
			return 0;
		}
		double each = baseValue(stack.getItem());
		if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
			each *= Math.max(0.1, 1 - stack.getDamageValue() / (double) stack.getMaxDamage());
		}
		int tier = apothRarity(stack);
		if (tier >= 0) {
			// Apotheosis: gems are worth more the rarer they are; affix gear is worth about what salvaging it gives
			each = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals("apotheosis:gem")
					? each * (1 << tier) : each + 2 * RARITY_MATERIAL[tier];
		}
		each += enchantValue(stack.get(DataComponents.ENCHANTMENTS));
		each += enchantValue(stack.get(DataComponents.STORED_ENCHANTMENTS));
		double total = each * stack.getCount();
		ItemContainerContents container = stack.get(DataComponents.CONTAINER);
		if (container != null) {
			for (ItemStack inner : container.nonEmptyItems()) {
				total += value(inner);
			}
		}
		BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
		if (bundle != null) {
			for (ItemStack inner : bundle.items()) {
				total += value(inner);
			}
		}
		return total;
	}

	/** Matter for one rarity material, common to mythic. */
	private static final double[] RARITY_MATERIAL = {2, 5, 12, 30, 80};
	private static final String[] RARITIES = {"common", "uncommon", "rare", "epic", "mythic"};

	/** Apotheosis rarity of a gem or affix item (0 = common ... 4 = mythic), or -1. Read by name, so no compile dependency. */
	private static int apothRarity(ItemStack stack) {
		for (net.minecraft.core.component.TypedDataComponent<?> c : stack.getComponents()) {
			ResourceLocation id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(c.type());
			if (id == null || !id.getNamespace().equals("apotheosis") || !id.getPath().equals("rarity")) {
				continue;
			}
			String v = String.valueOf(c.value()).toLowerCase(java.util.Locale.ROOT);
			// e.g. "apotheosis:rare"; check uncommon before common
			for (int i : new int[]{4, 3, 1, 2, 0}) {
				if (v.contains("apotheosis:" + RARITIES[i]) || v.equals(RARITIES[i])) {
					return i;
				}
			}
		}
		return -1;
	}

	public static double baseValue(Item item) {
		Double v = values.get(item);
		return v != null ? v : fallback(item);
	}

	private static double enchantValue(ItemEnchantments ench) {
		if (ench == null || ench.isEmpty()) {
			return 0;
		}
		double v = 0;
		for (Object2IntMap.Entry<Holder<Enchantment>> e : ench.entrySet()) {
			if (!e.getKey().is(EnchantmentTags.CURSE)) {
				int lvl = e.getIntValue();
				v += 4 * lvl + (e.getKey().is(EnchantmentTags.TREASURE) ? 10 : 0);
			}
		}
		return v;
	}

	/** Items with no recipe and no hand-picked value: go by rarity. Creative-only things are worth nothing. */
	private static double fallback(Item item) {
		if (item instanceof SpawnEggItem || item == Items.AIR) {
			return 0;
		}
		// Mods can add their own rarities (NeoForge lets them extend the enum), so anything unknown counts as epic.
		return switch (new ItemStack(item).getRarity()) {
			case COMMON -> 0.1;
			case UNCOMMON -> 3;
			case RARE -> 10;
			case EPIC -> 30;
			default -> 30;
		};
	}

	private static final String[] WORTHLESS = {"bedrock", "barrier", "command_block", "chain_command_block", "repeating_command_block",
			"command_block_minecart", "structure_block", "structure_void", "jigsaw", "light", "debug_stick", "knowledge_book",
			"spawner", "trial_spawner", "vault", "end_portal_frame", "reinforced_deepslate", "budding_amethyst", "petrified_oak_slab",
			"player_head", "written_book", "filled_map"};

	/** Hand-picked values for things that come out of the world rather than a crafting table. */
	private static Map<String, Double> baseTable() {
		Map<String, Double> m = new HashMap<>();
		for (String junk : new String[]{"dirt", "coarse_dirt", "rooted_dirt", "grass_block", "podzol", "mycelium", "mud", "cobblestone",
				"cobbled_deepslate", "netherrack", "gravel", "sand", "red_sand", "andesite", "diorite", "granite", "tuff", "calcite",
				"end_stone", "soul_sand", "soul_soil", "blackstone", "basalt", "dripstone_block", "pointed_dripstone", "snowball",
				"stone", "deepslate", "cobweb", "short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "vine", "seagrass",
				"sculk_vein", "glow_lichen", "hanging_roots", "rotten_flesh", "poisonous_potato", "magma_block", "ice", "snow_block"}) {
			m.put(junk, 0.02);
		}
		for (String cheap : new String[]{"clay_ball", "flint", "wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds", "kelp",
				"bamboo", "cactus", "melon_slice", "sugar_cane", "red_mushroom", "brown_mushroom", "crimson_fungus", "warped_fungus",
				"lily_pad", "moss_block", "sculk", "feather", "rabbit_hide", "bone", "string", "spider_eye", "ink_sac", "egg",
				"wheat", "carrot", "potato", "beetroot", "sweet_berries", "glow_berries", "cocoa_beans", "nether_sprouts",
				"crimson_roots", "warped_roots", "twisting_vines", "weeping_vines", "spore_blossom", "small_dripleaf", "big_dripleaf"}) {
			m.put(cheap, 0.15);
		}
		Object[][] table = {
				{"coal", 1.0}, {"raw_copper", 0.5}, {"copper_ingot", 0.6}, {"raw_iron", 2.0}, {"iron_ingot", 2.0}, {"raw_gold", 4.0},
				{"gold_ingot", 4.0}, {"redstone", 0.8}, {"lapis_lazuli", 1.2}, {"quartz", 1.2}, {"amethyst_shard", 1.0},
				{"diamond", 16.0}, {"emerald", 12.0}, {"ancient_debris", 50.0}, {"netherite_scrap", 50.0}, {"ender_pearl", 6.0},
				{"blaze_rod", 5.0}, {"breeze_rod", 6.0}, {"ghast_tear", 10.0}, {"nether_star", 600.0}, {"shulker_shell", 30.0},
				{"elytra", 800.0}, {"totem_of_undying", 120.0}, {"heart_of_the_sea", 150.0}, {"nautilus_shell", 15.0},
				{"echo_shard", 25.0}, {"trident", 150.0}, {"dragon_egg", 1500.0}, {"dragon_breath", 20.0}, {"dragon_head", 300.0},
				{"wither_skeleton_skull", 80.0}, {"skeleton_skull", 10.0}, {"zombie_head", 10.0}, {"creeper_head", 20.0},
				{"piglin_head", 15.0}, {"slime_ball", 1.5}, {"leather", 1.0}, {"gunpowder", 1.0}, {"glowstone_dust", 0.8},
				{"prismarine_shard", 1.0}, {"prismarine_crystals", 1.5}, {"phantom_membrane", 4.0}, {"rabbit_foot", 4.0},
				{"glow_ink_sac", 1.5}, {"honeycomb", 1.0}, {"honey_bottle", 1.0}, {"apple", 0.5}, {"pumpkin", 0.3}, {"melon", 0.4},
				{"nether_wart", 0.5}, {"chorus_fruit", 0.4}, {"beef", 0.4}, {"porkchop", 0.4}, {"mutton", 0.4}, {"chicken", 0.4},
				{"rabbit", 0.4}, {"cod", 0.4}, {"salmon", 0.4}, {"tropical_fish", 0.5}, {"pufferfish", 0.6}, {"saddle", 20.0},
				{"name_tag", 15.0}, {"enchanted_golden_apple", 300.0}, {"experience_bottle", 3.0}, {"turtle_scute", 5.0},
				{"armadillo_scute", 3.0}, {"heavy_core", 200.0}, {"trial_key", 10.0}, {"ominous_trial_key", 30.0}, {"goat_horn", 10.0},
				{"sniffer_egg", 40.0}, {"torchflower_seeds", 5.0}, {"pitcher_pod", 5.0}, {"obsidian", 1.5}, {"crying_obsidian", 3.0},
				{"sponge", 20.0}, {"wet_sponge", 20.0}, {"sculk_catalyst", 10.0}, {"sculk_shrieker", 10.0}, {"sculk_sensor", 5.0},
				{"turtle_egg", 5.0}, {"frogspawn", 2.0}, {"bell", 15.0}, {"wither_rose", 10.0}, {"music_disc_pigstep", 60.0},
				{"music_disc_otherside", 60.0}, {"music_disc_5", 40.0}, {"music_disc_relic", 40.0}, {"music_disc_creator", 60.0},
				{"music_disc_precipice", 40.0}, {"disc_fragment_5", 5.0}, {"enchanted_book", 2.0}, {"milk_bucket", 2.5},
				{"water_bucket", 2.2}, {"lava_bucket", 3.0}, {"powder_snow_bucket", 2.2}, {"ice", 0.05}, {"blue_ice", 2.0},
				{"mob_head", 10.0}, {"wind_charge", 1.5}, {"ominous_bottle", 15.0}, {"cobweb", 0.2}, {"glass_bottle", 0.05}};
		for (Object[] row : table) {
			m.put((String) row[0], (Double) row[1]);
		}
		for (String w : WORTHLESS) {
			m.put(w, 0.0);
		}
		// Apotheosis: the rarity materials you get from salvaging (common -> mythic), and gems (scaled by rarity in value())
		Object[][] apotheosis = {{"apotheosis:mysterious_scrap_metal", 2.0}, {"apotheosis:timeworn_fabric", 5.0},
				{"apotheosis:luminous_crystal_shard", 12.0}, {"apotheosis:arcane_sands", 30.0}, {"apotheosis:godforged_pearl", 80.0},
				{"apotheosis:gem_dust", 2.0}, {"apotheosis:gem", 6.0}, {"apotheosis:boss_spawner", 0.0}, {"apotheosis:boss_summoner", 40.0}};
		// Twilight Forest: boss drops, loot and things that grow there (crafted things follow their recipes)
		Object[][] twilight = {{"twilightforest:naga_scale", 8.0}, {"twilightforest:raven_feather", 1.0},
				{"twilightforest:liveroot", 1.0}, {"twilightforest:torchberries", 0.8}, {"twilightforest:steeleaf_ingot", 3.0},
				{"twilightforest:raw_ironwood", 2.5}, {"twilightforest:fiery_blood", 25.0}, {"twilightforest:fiery_tears", 25.0},
				{"twilightforest:carminite", 10.0}, {"twilightforest:borer_essence", 3.0}, {"twilightforest:armor_shard", 3.0},
				{"twilightforest:arctic_fur", 4.0}, {"twilightforest:alpha_yeti_fur", 30.0}, {"twilightforest:hydra_chop", 15.0},
				{"twilightforest:raw_meef", 0.5}, {"twilightforest:transformation_powder", 6.0}, {"twilightforest:firefly", 1.0},
				{"twilightforest:cicada", 1.0}, {"twilightforest:moonworm", 1.0}, {"twilightforest:moonworm_queen", 60.0},
				{"twilightforest:naga_trophy", 80.0}, {"twilightforest:lich_trophy", 120.0}, {"twilightforest:minoshroom_trophy", 120.0},
				{"twilightforest:hydra_trophy", 200.0}, {"twilightforest:knight_phantom_trophy", 200.0},
				{"twilightforest:ur_ghast_trophy", 300.0}, {"twilightforest:alpha_yeti_trophy", 200.0},
				{"twilightforest:snow_queen_trophy", 300.0}, {"twilightforest:quest_ram_trophy", 100.0},
				{"twilightforest:lamp_of_cinders", 150.0}, {"twilightforest:ore_magnet", 40.0}, {"twilightforest:maze_map_focus", 8.0},
				{"twilightforest:uncrafting_table", 30.0}, {"twilightforest:lifedrain_scepter", 80.0},
				{"twilightforest:twilight_scepter", 60.0}, {"twilightforest:zombie_scepter", 60.0},
				{"twilightforest:fortification_scepter", 80.0}, {"twilightforest:triple_bow", 60.0}, {"twilightforest:seeker_bow", 60.0},
				{"twilightforest:ender_bow", 60.0}, {"twilightforest:ice_bow", 60.0}, {"twilightforest:mazebreaker_pickaxe", 200.0},
				{"twilightforest:giant_pickaxe", 100.0}, {"twilightforest:giant_sword", 100.0},
				{"twilightforest:cube_of_annihilation", 300.0}, {"twilightforest:block_and_chain", 80.0},
				{"twilightforest:peacock_feather_fan", 40.0}, {"twilightforest:charm_of_life_1", 30.0},
				{"twilightforest:charm_of_keeping_1", 20.0}, {"twilightforest:phantom_helmet", 120.0},
				{"twilightforest:phantom_chestplate", 120.0}, {"twilightforest:transformation_log_core", 100.0},
				{"twilightforest:time_log_core", 100.0}, {"twilightforest:mining_log_core", 100.0},
				{"twilightforest:sorting_log_core", 100.0}, {"twilightforest:naga_boss_spawner", 0.0},
				{"twilightforest:lich_boss_spawner", 0.0}, {"twilightforest:hydra_boss_spawner", 0.0},
				{"twilightforest:ur_ghast_boss_spawner", 0.0}, {"twilightforest:alpha_yeti_boss_spawner", 0.0},
				{"twilightforest:minoshroom_boss_spawner", 0.0}};
		for (Object[][] modTable : new Object[][][]{apotheosis, twilight}) {
			for (Object[] row : modTable) {
				m.put((String) row[0], (Double) row[1]);
			}
		}
		return m;
	}

	private static TagKey<Item> tag(String ns, String path) {
		return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(ns, path));
	}

	private static void computeValues(MinecraftServer s) {
		values.clear();
		Map<Item, Double> fixed = new IdentityHashMap<>();
		baseTable().forEach((id, v) -> {
			ResourceLocation key = id.indexOf(':') >= 0 ? ResourceLocation.tryParse(id) : ResourceLocation.withDefaultNamespace(id);
			if (key != null) {
				BuiltInRegistries.ITEM.getOptional(key).ifPresent(item -> fixed.put(item, v));
			}
		});
		// Things from other mods that are raw materials: seed them by their common tags.
		Object[][] tagSeeds = {{tag("c", "gems"), 8.0}, {tag("c", "ingots"), 2.5}, {tag("c", "raw_materials"), 2.0},
				{tag("c", "ores"), 3.0}, {ItemTags.LOGS, 0.5}, {ItemTags.FLOWERS, 0.1}, {ItemTags.SAPLINGS, 0.1},
				{ItemTags.LEAVES, 0.02}, {tag("c", "music_discs"), 30.0}};
		values.putAll(fixed);

		// Collect every recipe as (ingredients -> output) once.
		record Flat(List<Ingredient> in, Item out, int count, boolean mixes) {
		}
		List<Flat> recipes = new ArrayList<>();
		HolderLookup.Provider lookup = s.registryAccess();
		for (RecipeHolder<?> entry : s.getRecipeManager().getRecipes()) {
			try {
				Recipe<?> r = entry.value();
				ItemStack result = r.getResultItem(lookup);
				if (result == null || result.isEmpty()) {
					continue;
				}
				List<Ingredient> in = new ArrayList<>();
				for (Ingredient i : r.getIngredients()) {
					if (!i.isEmpty()) {
						in.add(i);
					}
				}
				if (in.isEmpty()) {
					in = ingredientFields(r); // smithing and some modded machines keep theirs in fields
				}
				if (in.isEmpty()) {
					continue;
				}
				long distinct = in.stream().map(i -> i.getStackingIds().toString()).distinct().count();
				recipes.add(new Flat(in, result.getItem(), result.getCount(), distinct >= 2));
			} catch (Throwable t) {
				// a broken modded recipe shouldn't stop the rest
			}
		}

		// Price everything the recipes can reach. Then give raw materials from other mods a value by their common tags
		// (only the ones the recipes couldn't price, so a netherite ingot isn't "just an ingot"), and go again.
		resolve(recipes.stream().map(f -> new Object[]{f.in, f.out, f.count, f.mixes}).toList(), fixed);
		for (Object[] seed : tagSeeds) {
			@SuppressWarnings("unchecked") TagKey<Item> t = (TagKey<Item>) seed[0];
			for (Holder<Item> e : BuiltInRegistries.ITEM.getTagOrEmpty(t)) {
				if (!values.containsKey(e.value())) {
					values.put(e.value(), (Double) seed[1]);
					fixed.put(e.value(), (Double) seed[1]);
				}
			}
		}
		resolve(recipes.stream().map(f -> new Object[]{f.in, f.out, f.count, f.mixes}).toList(), fixed);
	}

	/** Keeps taking the cheapest known way to make each item until nothing changes. Hand-set values never change. */
	@SuppressWarnings("unchecked")
	private static void resolve(List<Object[]> recipes, Map<Item, Double> fixed) {
		for (int pass = 0; pass < 16; pass++) {
			boolean changed = false;
			for (Object[] f : recipes) {
				List<Ingredient> in = (List<Ingredient>) f[0];
				Item out = (Item) f[1];
				if (fixed.containsKey(out)) {
					continue;
				}
				double cost = 0;
				boolean known = true;
				for (Ingredient i : in) {
					double best = Double.MAX_VALUE;
					for (ItemStack option : i.getItems()) {
						Double v = values.get(option.getItem());
						if (v != null && v < best) {
							best = v;
						}
					}
					if (best == Double.MAX_VALUE) {
						known = false;
						break;
					}
					cost += best;
				}
				if (!known) {
					continue;
				}
				double each = cost * ((Boolean) f[3] ? CRAFT_BONUS : 1.0) / Math.max(1, (Integer) f[2]);
				Double old = values.get(out);
				if (old == null || each < old - 1e-9) {
					values.put(out, each);
					changed = true;
				}
			}
			if (!changed) {
				break;
			}
		}
	}

	private static List<Ingredient> ingredientFields(Object recipe) {
		List<Ingredient> out = new ArrayList<>();
		for (Class<?> c = recipe.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field f : c.getDeclaredFields()) {
				if (f.getType() == Ingredient.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
					try {
						f.setAccessible(true);
						Ingredient i = (Ingredient) f.get(recipe);
						if (i != null && !i.isEmpty()) {
							out.add(i);
						}
					} catch (Exception ignored) {
					}
				}
			}
		}
		return out;
	}
}
