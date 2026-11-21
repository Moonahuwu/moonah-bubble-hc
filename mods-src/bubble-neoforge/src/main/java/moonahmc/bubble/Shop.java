package moonahmc.bubble;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import moonahmc.bubble.net.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The shop: sections of items bought with Strange Matter, stored in config/bubble-shop.json.
 * Operators can change prices and amounts, add the item they're holding, and add sections from the shop screen.
 */
public final class Shop {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int MAX_PRICE = 1_000_000;

	/** An item for sale. stack is null when its mod isn't installed; the raw JSON is kept so it isn't lost. */
	static final class Entry {
		JsonElement itemJson;
		ItemStack stack;
		int price;
		/** Instead of giving the item: "apothic_roll", "apothic_roll:rare" or "apothic_roll:epic". */
		String action;
	}

	static final class Section {
		String name;
		String icon;
		boolean charging;
		/** The Video tab: its one entry's price is what showing a video costs. */
		boolean video;
		final List<Entry> entries = new ArrayList<>();
	}

	private static final List<Section> sections = new ArrayList<>();
	/** Energy you get per Strange Matter in the Charging tab. */
	private static int chargeRate = 2000;
	/** Admin switch: buying from the shop tabs (and charging) is off; Gamble and selling still work. */
	static boolean closed;
	private static MinecraftServer server;
	private static Path file;

	private Shop() {
	}

	static void onServerStarted(MinecraftServer s) {
		server = s;
		file = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("bubble-shop.json");
		load();
	}

	static void onServerStopped() {
		server = null;
	}

	private static RegistryOps<JsonElement> ops() {
		return RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
	}

	// ------------------------------------------------------------------ file

	private static void load() {
		sections.clear();
		try {
			JsonObject root;
			if (Files.exists(file)) {
				root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			} else {
				root = defaults();
			}
			if (root.has("chargeRate")) {
				chargeRate = Math.max(1, root.get("chargeRate").getAsInt());
			}
			closed = root.has("shopClosed") && root.get("shopClosed").getAsBoolean();
			JsonArray secs = withNewDefaults(root);
			int missing = 0;
			for (JsonElement se : secs) {
				JsonObject so = se.getAsJsonObject();
				Section sec = new Section();
				sec.name = so.get("name").getAsString();
				sec.icon = so.has("icon") ? so.get("icon").getAsString() : "";
				sec.charging = so.has("charging") && so.get("charging").getAsBoolean();
				sec.video = so.has("video") && so.get("video").getAsBoolean();
				if (so.has("items")) {
					for (JsonElement ie : so.getAsJsonArray("items")) {
						JsonObject io = ie.getAsJsonObject();
						Entry e = new Entry();
						e.itemJson = io.get("item");
						e.price = io.get("price").getAsInt();
						e.action = io.has("action") ? io.get("action").getAsString() : null;
						e.stack = ItemStack.CODEC.parse(ops(), e.itemJson).result().orElse(null);
						if (e.stack == null) {
							missing++;
						}
						sec.entries.add(e);
					}
				}
				sections.add(sec);
			}
			if (missing > 0) {
				Bubble.LOG.info("Shop: {} items are hidden because their mods aren't installed", missing);
			}
		} catch (Exception e) {
			Bubble.LOG.error("config/bubble-shop.json could not be read; the shop is empty until it's fixed", e);
		}
		save();
	}

	/** Default tab names this shop file has already been given (so deleting one in edit mode keeps it deleted). */
	private static final Set<String> defaultsSeen = new LinkedHashSet<>();

	/**
	 * The file's tabs plus any default tab added in a newer Bubble version (e.g. Apotheosis, Twilight Forest) that this
	 * file has never had. New tabs go in before Charging. Tabs an op deleted aren't brought back.
	 */
	private static JsonArray withNewDefaults(JsonObject root) {
		JsonArray secs = root.getAsJsonArray("sections");
		defaultsSeen.clear();
		if (root.has("defaultsSeen")) {
			root.getAsJsonArray("defaultsSeen").forEach(e -> defaultsSeen.add(e.getAsString()));
		} else {
			secs.forEach(e -> defaultsSeen.add(e.getAsJsonObject().get("name").getAsString()));
		}
		List<JsonElement> fresh = new ArrayList<>();
		for (JsonElement d : defaults().getAsJsonArray("sections")) {
			if (defaultsSeen.add(d.getAsJsonObject().get("name").getAsString())) {
				fresh.add(d);
			}
		}
		if (fresh.isEmpty()) {
			return migrate(secs);
		}
		JsonArray out = new JsonArray();
		boolean placed = false;
		for (JsonElement e : secs) {
			JsonObject so = e.getAsJsonObject();
			if (!placed && so.has("charging") && so.get("charging").getAsBoolean()) {
				fresh.forEach(out::add);
				placed = true;
			}
			out.add(e);
		}
		if (!placed) {
			fresh.forEach(out::add);
		}
		Bubble.LOG.info("Shop: added {} new tab(s) to config/bubble-shop.json", fresh.size());
		return migrate(out);
	}

	/** One-off changes to shop files from older versions (each runs once; markers live in defaultsSeen). */
	private static JsonArray migrate(JsonArray secs) {
		// 1.5.1: the Twilight Forest tab became a case in the Gamble tab
		if (defaultsSeen.add("-retired:Twilight Forest")) {
			for (int i = secs.size() - 1; i >= 0; i--) {
				if ("Twilight Forest".equals(secs.get(i).getAsJsonObject().get("name").getAsString())) {
					secs.remove(i);
					Bubble.LOG.info("Shop: removed the Twilight Forest tab (it's a Gamble case now)");
				}
			}
		}
		if (defaultsSeen.add("+Gamble:case:twilight")) {
			for (JsonElement e : secs) {
				JsonObject so = e.getAsJsonObject();
				if (!"Gamble".equals(so.get("name").getAsString()) || !so.has("items")) {
					continue;
				}
				boolean has = false;
				for (JsonElement ie : so.getAsJsonArray("items")) {
					JsonObject io = ie.getAsJsonObject();
					has |= io.has("action") && "case:twilight".equals(io.get("action").getAsString());
				}
				if (!has) {
					so.getAsJsonArray("items").add(twilightCase());
					Bubble.LOG.info("Shop: added the Twilight Forest Case to the Gamble tab");
				}
			}
		}
		return secs;
	}

	private static void save() {
		JsonObject root = new JsonObject();
		root.addProperty("chargeRate", chargeRate);
		root.addProperty("shopClosed", closed);
		JsonArray seen = new JsonArray();
		defaultsSeen.forEach(seen::add);
		root.add("defaultsSeen", seen);
		JsonArray arr = new JsonArray();
		for (Section sec : sections) {
			JsonObject so = new JsonObject();
			so.addProperty("name", sec.name);
			so.addProperty("icon", sec.icon);
			if (sec.charging) {
				so.addProperty("charging", true);
			}
			if (sec.video) {
				so.addProperty("video", true);
			}
			JsonArray items = new JsonArray();
			for (Entry e : sec.entries) {
				JsonObject io = new JsonObject();
				JsonElement json = e.stack != null ? ItemStack.CODEC.encodeStart(ops(), e.stack).result().orElse(e.itemJson) : e.itemJson;
				io.add("item", json);
				io.addProperty("price", e.price);
				if (e.action != null) {
					io.addProperty("action", e.action);
				}
				items.add(io);
			}
			if (!sec.charging) {
				so.add("items", items);
			}
			arr.add(so);
		}
		root.add("sections", arr);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(root));
		} catch (Exception e) {
			Bubble.LOG.error("Could not save config/bubble-shop.json", e);
		}
	}

	private static JsonObject defaults() {
		Object[][][] data = {
				{{"Essentials", "minecraft:bread"},
						{"minecraft:bread", 8, 6}, {"minecraft:cooked_beef", 8, 10}, {"minecraft:golden_carrot", 8, 24},
						{"minecraft:torch", 32, 4}, {"minecraft:oak_log", 32, 30}, {"minecraft:white_bed", 1, 6},
						{"minecraft:water_bucket", 1, 8}, {"minecraft:arrow", 32, 10}, {"minecraft:shield", 1, 12},
						{"minecraft:firework_rocket", 16, 20}, {"minecraft:ender_pearl", 4, 60}, {"minecraft:experience_bottle", 8, 60},
						{"minecraft:name_tag", 1, 40}, {"minecraft:saddle", 1, 50}},
				{{"Materials", "minecraft:diamond"},
						{"minecraft:iron_ingot", 8, 40}, {"minecraft:gold_ingot", 8, 80}, {"minecraft:diamond", 1, 40},
						{"minecraft:emerald", 1, 30}, {"minecraft:lapis_lazuli", 16, 45}, {"minecraft:redstone", 16, 32},
						{"minecraft:quartz", 16, 45}, {"minecraft:obsidian", 8, 30}, {"minecraft:blaze_rod", 4, 50},
						{"minecraft:slime_ball", 8, 30}, {"minecraft:leather", 8, 20}, {"minecraft:netherite_scrap", 1, 140}},
				{{"Gear", "minecraft:diamond_pickaxe"},
						{"minecraft:diamond_pickaxe", 1, 120}, {"minecraft:diamond_sword", 1, 90}, {"minecraft:diamond_chestplate", 1, 180},
						{"minecraft:bow", 1, 15}, {"minecraft:crossbow", 1, 25}, {"minecraft:golden_apple", 1, 50},
						{"minecraft:totem_of_undying", 1, 300}, {"minecraft:trident", 1, 400}, {"minecraft:elytra", 1, 1500},
						{"minecraft:enchanted_golden_apple", 1, 700}},
				// Sophisticated Backpacks (NeoForge) first, then Traveler's Backpack; whichever isn't installed is hidden.
				{{"Backpacks", "sophisticatedbackpacks:backpack"},
						{"sophisticatedbackpacks:backpack", 1, 40}, {"sophisticatedbackpacks:copper_backpack", 1, 70},
						{"sophisticatedbackpacks:iron_backpack", 1, 110}, {"sophisticatedbackpacks:gold_backpack", 1, 200},
						{"sophisticatedbackpacks:diamond_backpack", 1, 400}, {"sophisticatedbackpacks:netherite_backpack", 1, 900},
						{"travelersbackpack:standard", 1, 60}, {"travelersbackpack:backpack_tank", 1, 30},
						{"travelersbackpack:hose", 1, 20}, {"travelersbackpack:red_sleeping_bag", 1, 15},
						{"travelersbackpack:fox", 1, 80}, {"travelersbackpack:bee", 1, 80}, {"travelersbackpack:villager", 1, 80},
						{"travelersbackpack:creeper", 1, 100}, {"travelersbackpack:iron_golem", 1, 120}, {"travelersbackpack:enderman", 1, 120},
						{"travelersbackpack:warden", 1, 300}, {"travelersbackpack:wither", 1, 300}, {"travelersbackpack:dragon", 1, 400}},
				{{"Backpack upgrades", "sophisticatedbackpacks:stack_upgrade_tier_1"},
						{"sophisticatedbackpacks:upgrade_base", 1, 20}, {"sophisticatedbackpacks:pickup_upgrade", 1, 50},
						{"sophisticatedbackpacks:advanced_pickup_upgrade", 1, 120}, {"sophisticatedbackpacks:magnet_upgrade", 1, 120},
						{"sophisticatedbackpacks:advanced_magnet_upgrade", 1, 260}, {"sophisticatedbackpacks:feeding_upgrade", 1, 80},
						{"sophisticatedbackpacks:advanced_feeding_upgrade", 1, 180}, {"sophisticatedbackpacks:refill_upgrade", 1, 90},
						{"sophisticatedbackpacks:restock_upgrade", 1, 70}, {"sophisticatedbackpacks:deposit_upgrade", 1, 70},
						{"sophisticatedbackpacks:void_upgrade", 1, 50}, {"sophisticatedbackpacks:compacting_upgrade", 1, 90},
						{"sophisticatedbackpacks:crafting_upgrade", 1, 60}, {"sophisticatedbackpacks:stonecutter_upgrade", 1, 50},
						{"sophisticatedbackpacks:smelting_upgrade", 1, 70}, {"sophisticatedbackpacks:auto_smelting_upgrade", 1, 160},
						{"sophisticatedbackpacks:tool_swapper_upgrade", 1, 120}, {"sophisticatedbackpacks:tank_upgrade", 1, 80},
						{"sophisticatedbackpacks:battery_upgrade", 1, 100}, {"sophisticatedbackpacks:jukebox_upgrade", 1, 50},
						{"sophisticatedbackpacks:anvil_upgrade", 1, 120}, {"sophisticatedbackpacks:smithing_upgrade", 1, 120},
						{"sophisticatedbackpacks:stack_upgrade_starter_tier", 1, 60}, {"sophisticatedbackpacks:stack_upgrade_tier_1", 1, 120},
						{"sophisticatedbackpacks:stack_upgrade_tier_2", 1, 240}, {"sophisticatedbackpacks:stack_upgrade_tier_3", 1, 480},
						{"sophisticatedbackpacks:stack_upgrade_tier_4", 1, 960}, {"sophisticatedbackpacks:inception_upgrade", 1, 600},
						{"sophisticatedbackpacks:everlasting_upgrade", 1, 800},
						{"travelersbackpack:blank_upgrade", 1, 25}, {"travelersbackpack:iron_tier_upgrade", 1, 80},
						{"travelersbackpack:gold_tier_upgrade", 1, 160}, {"travelersbackpack:diamond_tier_upgrade", 1, 320},
						{"travelersbackpack:netherite_tier_upgrade", 1, 640}, {"travelersbackpack:crafting_upgrade", 1, 60},
						{"travelersbackpack:furnace_upgrade", 1, 60}, {"travelersbackpack:smoker_upgrade", 1, 60},
						{"travelersbackpack:blast_furnace_upgrade", 1, 90}, {"travelersbackpack:tanks_upgrade", 1, 70},
						{"travelersbackpack:pickup_upgrade", 1, 80}, {"travelersbackpack:magnet_upgrade", 1, 150},
						{"travelersbackpack:refill_upgrade", 1, 90}, {"travelersbackpack:feeding_upgrade", 1, 90},
						{"travelersbackpack:void_upgrade", 1, 50}, {"travelersbackpack:jukebox_upgrade", 1, 50},
						{"travelersbackpack:lantern_upgrade", 1, 40}},
				{{"Apotheosis", "apotheosis:sigil_of_socketing"},
						{"apotheosis:sigil_of_socketing", 1, 60}, {"apotheosis:sigil_of_withdrawal", 1, 50},
						{"apotheosis:sigil_of_rebirth", 1, 80}, {"apotheosis:sigil_of_enhancement", 1, 120},
						{"apotheosis:sigil_of_unnaming", 1, 30}, {"apotheosis:gem_dust", 16, 50},
						{"apotheosis:mysterious_scrap_metal", 8, 25}, {"apotheosis:timeworn_fabric", 4, 30},
						{"apotheosis:luminous_crystal_shard", 2, 40}, {"apotheosis:arcane_sands", 1, 50},
						{"apotheosis:godforged_pearl", 1, 130}, {"apotheosis:boss_summoner", 1, 150}},
				{{"Lucky Blocks", "lucky:lucky_block"},
						{"lucky:lucky_block", 1, 30}, {"lucky:lucky_block", 8, 220}, {"lucky:lucky_block", 32, 800}},
				{{"Fun", "myluckyblock:my_lucky_block"},
						{"myluckyblock:my_lucky_block", 1, 25}, {"minecraft:cake", 1, 8}, {"minecraft:jukebox", 1, 25},
						{"minecraft:music_disc_pigstep", 1, 150}, {"minecraft:firework_rocket", 64, 70}, {"minecraft:lead", 2, 10}},
				// Estrogen: Girl Power lets you dash
				{{"Estrogen", "estrogen:estrogen_pill"},
						{"estrogen:estrogen_pill", 3, 40}, {"estrogen:crystal_estrogen_pill", 1, 60}, {"estrogen:estrogen_chip_cookie", 8, 30},
						{"estrogen:estrogen_patch", 1, 120}, {"estrogen:liquid_estrogen_bucket", 1, 60}, {"estrogen:thigh_highs", 1, 80},
						{"estrogen:gender_change_potion", 1, 50}, {"estrogen:moth_elytra", 1, 1200}, {"estrogen:dreamcatcher", 1, 30},
						{"estrogen:moth_wool", 16, 20}, {"estrogen:cookie_jar", 1, 15}, {"estrogen:colon_three", 1, 5}},
				// WaterFrames: frames and TVs that show a picture or video from a link
				{{"Screens", "waterframes:tv"},
						{"waterframes:frame", 1, 25}, {"waterframes:tv", 1, 50}, {"waterframes:big_tv", 1, 120},
						{"waterframes:tv_box", 1, 40}, {"waterframes:projector", 1, 60}, {"waterframes:remote", 1, 30}},
		};
		JsonObject root = new JsonObject();
		root.addProperty("chargeRate", 2000);
		JsonArray arr = new JsonArray();
		for (Object[][] sec : data) {
			JsonObject so = new JsonObject();
			so.addProperty("name", (String) sec[0][0]);
			so.addProperty("icon", (String) sec[0][1]);
			JsonArray items = new JsonArray();
			for (int i = 1; i < sec.length; i++) {
				JsonObject item = new JsonObject();
				item.addProperty("id", (String) sec[i][0]);
				item.addProperty("count", (Integer) sec[i][1]);
				JsonObject io = new JsonObject();
				io.add("item", item);
				io.addProperty("price", (Integer) sec[i][2]);
				items.add(io);
			}
			so.add("items", items);
			arr.add(so);
		}
		// Gamble: CS-style cases (contents in config/bubble-cases.json)
		JsonObject gamble = new JsonObject();
		gamble.addProperty("name", "Gamble");
		gamble.addProperty("icon", "minecraft:chest");
		JsonArray caseItems = new JsonArray();
		caseItems.add(caseOffer("mod", "minecraft:chest", "Mod Case", "gold", "Items from every mod in the pack", 80));
		caseItems.add(caseOffer("progression", "minecraft:ender_chest", "Progression Case", "dark_purple", "Pearls, eyes of ender, blaze rods, netherite...", 150));
		caseItems.add(caseOffer("weapons", "minecraft:trapped_chest", "Weapon Case", "red", "Swords, bows and Apotheosis gear", 150));
		caseItems.add(twilightCase());
		gamble.add("items", caseItems);
		arr.add(gamble);
		// Apothic rolls: give a random tool or weapon in your inventory a random Apotheosis rarity (with affixes)
		JsonObject rolls = new JsonObject();
		rolls.addProperty("name", "Apothic Rolls");
		rolls.addProperty("icon", "apotheosis:sigil_of_enhancement");
		JsonArray rollItems = new JsonArray();
		rollItems.add(rollOffer("apothic_roll", "apotheosis:gem_dust", "Apothic Roll", "gold", "Any rarity, common to mythic", 150));
		rollItems.add(rollOffer("apothic_roll:rare", "apotheosis:luminous_crystal_shard", "Rare+ Apothic Roll", "blue", "Rare, epic or mythic", 450));
		rollItems.add(rollOffer("apothic_roll:epic", "apotheosis:arcane_sands", "Epic+ Apothic Roll", "light_purple", "Epic or mythic", 1200));
		rolls.add("items", rollItems);
		arr.add(rolls);
		// Video: paste a link, everyone sees it (WaterMedia). The one item's price is the cost.
		JsonObject video = new JsonObject();
		video.addProperty("name", "Video");
		video.addProperty("icon", "waterframes:tv");
		video.addProperty("video", true);
		JsonArray videoItems = new JsonArray();
		JsonObject tv = new JsonObject();
		tv.addProperty("id", "waterframes:tv");
		tv.addProperty("count", 1);
		JsonObject videoOffer = new JsonObject();
		videoOffer.add("item", tv);
		videoOffer.addProperty("price", 250);
		videoItems.add(videoOffer);
		video.add("items", videoItems);
		arr.add(video);
		JsonObject charging = new JsonObject();
		charging.addProperty("name", "Charging");
		charging.addProperty("icon", "oritech:basic_battery");
		charging.addProperty("charging", true);
		arr.add(charging);
		root.add("sections", arr);
		return root;
	}

	private static JsonObject twilightCase() {
		return caseOffer("twilight", "twilightforest:magic_map_focus", "Twilight Forest Case", "dark_green",
				"Ingots, charms, bows, scepters, trophies...", 120);
	}

	private static JsonObject caseOffer(String id, String icon, String name, String color, String what, int price) {
		JsonObject components = new JsonObject();
		components.addProperty("minecraft:custom_name", "{\"text\":\"" + name + "\",\"color\":\"" + color + "\",\"italic\":false}");
		JsonArray lore = new JsonArray();
		lore.add("{\"text\":\"" + what + "\",\"color\":\"gray\",\"italic\":false}");
		String[][] odds = {{"Common 79.92%", "#4B69FF"}, {"Uncommon 15.98%", "#8847FF"}, {"Rare 3.2%", "#D32CE6"}, {"Epic 0.64%", "#EB4B4B"},
				{"Legendary 0.26%", "#E4AE39"}};
		for (String[] o : odds) {
			lore.add("{\"text\":\"  " + o[0] + "\",\"color\":\"" + o[1] + "\",\"italic\":false}");
		}
		lore.add("{\"text\":\"Click to open\",\"color\":\"dark_gray\",\"italic\":true}");
		components.add("minecraft:lore", lore);
		JsonObject item = new JsonObject();
		item.addProperty("id", icon);
		item.addProperty("count", 1);
		item.add("components", components);
		JsonObject io = new JsonObject();
		io.add("item", item);
		io.addProperty("price", price);
		io.addProperty("action", "case:" + id);
		return io;
	}

	private static JsonObject rollOffer(String action, String icon, String name, String color, String rarities, int price) {
		JsonObject components = new JsonObject();
		components.addProperty("minecraft:custom_name", "{\"text\":\"" + name + "\",\"color\":\"" + color + "\",\"italic\":false}");
		JsonArray lore = new JsonArray();
		lore.add("{\"text\":\"Rerolls a random tool or weapon\",\"color\":\"gray\",\"italic\":false}");
		lore.add("{\"text\":\"in your inventory: " + rarities + "\",\"color\":\"gray\",\"italic\":false}");
		lore.add("{\"text\":\"Replaces its current rarity and affixes\",\"color\":\"dark_gray\",\"italic\":true}");
		components.add("minecraft:lore", lore);
		JsonObject item = new JsonObject();
		item.addProperty("id", icon);
		item.addProperty("count", 1);
		item.add("components", components);
		JsonObject io = new JsonObject();
		io.add("item", item);
		io.addProperty("price", price);
		io.addProperty("action", action);
		return io;
	}

	// ------------------------------------------------------------------ sync

	/** You never pay less than an item sells for, so buying and selling can't make matter out of nothing. */
	static int effectivePrice(Entry e) {
		return (int) Math.max(e.price, Math.ceil(Matter.value(e.stack) - 1e-9));
	}

	private static ItemStack icon(Section sec) {
		ResourceLocation id = ResourceLocation.tryParse(sec.icon == null ? "" : sec.icon);
		if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
			return new ItemStack(BuiltInRegistries.ITEM.get(id));
		}
		for (Entry e : sec.entries) {
			if (e.stack != null) {
				return new ItemStack(e.stack.getItem());
			}
		}
		return new ItemStack(sec.charging ? net.minecraft.world.item.Items.REDSTONE : net.minecraft.world.item.Items.CHEST);
	}

	static void sendCatalog(ServerPlayer p) {
		if (server == null || !ServerPlayNetworking.canSend(p, ShopPayloads.Catalog.ID)) {
			return;
		}
		List<ShopPayloads.Section> out = new ArrayList<>();
		for (Section sec : sections) {
			List<ShopPayloads.Offer> offers = new ArrayList<>();
			for (Entry e : sec.entries) {
				if (e.stack != null) {
					offers.add(new ShopPayloads.Offer(e.stack.copy(), effectivePrice(e), Matter.value(e.stack)));
				}
			}
			if (hidden(sec)) {
				continue;
			}
			out.add(new ShopPayloads.Section(sec.name, icon(sec), sec.charging, offers, isGamble(sec), sec.video));
		}
		ServerPlayNetworking.send(p, new ShopPayloads.Catalog(p.hasPermissions(2), chargeRate, out, closed));
		Matter.sendBalance(p);
	}

	/** Tabs players don't see: Charging without an energy mod, Video without WaterMedia (or with no price). */
	private static boolean hidden(Section sec) {
		return (sec.charging && !EnergyCompat.available())
				|| (sec.video && (!net.neoforged.fml.ModList.get().isLoaded("watermedia") || sec.entries.stream().noneMatch(e -> e.stack != null)));
	}

	/** What showing a video costs (the Video tab's price), or -1 if there's no Video tab. */
	static int videoPrice() {
		for (Section sec : sections) {
			if (sec.video && !hidden(sec)) {
				for (Entry e : sec.entries) {
					if (e.stack != null) {
						return effectivePrice(e);
					}
				}
			}
		}
		return -1;
	}

	private static boolean isGamble(Section sec) {
		return sec.entries.stream().anyMatch(en -> en.action != null && en.action.startsWith("case:"));
	}

	/** The Admin tab's Close shop / Open shop. */
	static void setClosed(boolean value, String who) {
		closed = value;
		save();
		if (server != null) {
			broadcastCatalog();
			server.getPlayerList().broadcastSystemMessage(Component.literal(value
					? who + " closed the shop. Gamble and selling still work."
					: who + " opened the shop again.").withStyle(value ? ChatFormatting.RED : ChatFormatting.GREEN), false);
		}
	}

	private static void refuseClosed(ServerPlayer p) {
		p.displayClientMessage(Component.literal("The shop is closed. Cases in the Gamble tab still work.").withStyle(ChatFormatting.RED), true);
		p.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.6f, 1.2f);
	}

	private static void broadcastCatalog() {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			sendCatalog(p);
		}
	}

	/** The client numbers sections/offers without the hidden (missing-mod) ones; map back to the real lists. */
	private static Section visibleSection(int index) {
		int i = 0;
		for (Section sec : sections) {
			if (hidden(sec)) {
				continue;
			}
			if (i++ == index) {
				return sec;
			}
		}
		return null;
	}

	private static Entry visibleEntry(Section sec, int index) {
		int i = 0;
		for (Entry e : sec.entries) {
			if (e.stack != null && i++ == index) {
				return e;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ buying

	static void buy(ServerPlayer p, ShopPayloads.Buy b) {
		Section sec = visibleSection(b.section());
		Entry e = sec == null ? null : visibleEntry(sec, b.index());
		if (e == null) {
			sendCatalog(p);
			return;
		}
		if (closed && !isGamble(sec)) {
			refuseClosed(p);
			return;
		}
		if (sec.video) {
			return; // paid for through the Video tab's link box (VideoShare)
		}
		int times = Math.max(1, Math.min(64, b.times()));
		int price = effectivePrice(e);
		if (e.action != null) {
			runAction(p, e, price, Math.min(times, 5), b);
			return;
		}
		long cost = (long) price * times;
		if (!Matter.add(p, -cost)) {
			p.displayClientMessage(Component.literal("Not enough Strange Matter (" + Matter.format(Matter.balance(p)) + " / " + cost + ")")
					.withStyle(ChatFormatting.RED), true);
			p.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.6f, 1.2f);
			return;
		}
		for (int i = 0; i < times; i++) {
			p.getInventory().placeItemBackInInventory(e.stack.copy());
		}
		p.containerMenu.broadcastChanges();
		p.displayClientMessage(Component.literal("Bought " + (e.stack.getCount() * times) + "× ").withStyle(ChatFormatting.GRAY).append(e.stack.getHoverName())
				.append(Component.literal(" for " + cost).withStyle(ChatFormatting.AQUA)), true);
		p.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.6f, 1.1f);
	}

	/** Offers that do something instead of giving an item. Only charges for the ones that worked. */
	private static void runAction(ServerPlayer p, Entry e, int price, int times, ShopPayloads.Buy b) {
		if (e.action.startsWith("case:")) {
			Gamble.open(p, e.action.substring(5), price, b.section(), b.index(), times);
			return;
		}
		if (!e.action.startsWith("apothic_roll") || !net.neoforged.fml.ModList.get().isLoaded("apotheosis")) {
			p.displayClientMessage(Component.literal("This offer isn't available on this server.").withStyle(ChatFormatting.RED), true);
			return;
		}
		int minTier = e.action.endsWith(":epic") ? 3 : e.action.endsWith(":rare") ? 2 : 0;
		int done = 0;
		for (int i = 0; i < times; i++) {
			if (Matter.balance(p) < price) {
				if (done == 0) {
					p.displayClientMessage(Component.literal("Not enough Strange Matter (" + Matter.format(Matter.balance(p)) + " / " + price + ")")
							.withStyle(ChatFormatting.RED), true);
					p.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.6f, 1.2f);
				}
				break;
			}
			if (!ApothRoll.roll(p, minTier)) {
				break;
			}
			Matter.add(p, -price);
			done++;
		}
		p.containerMenu.broadcastChanges();
	}

	// ------------------------------------------------------------------ charging

	private static boolean energyApi() {
		return EnergyCompat.available();
	}

	static void sendCharges(ServerPlayer p) {
		if (!ServerPlayNetworking.canSend(p, ShopPayloads.ChargeList.ID)) {
			return;
		}
		ServerPlayNetworking.send(p, energyApi() ? new ShopPayloads.ChargeList(true, EnergyCompat.list(p, chargeRate))
				: new ShopPayloads.ChargeList(false, List.of()));
	}

	static void charge(ServerPlayer p, int slot) {
		if (!energyApi()) {
			return;
		}
		if (closed) {
			refuseClosed(p);
			return;
		}
		long energy = 0;
		int cost = 0;
		for (ShopPayloads.Chargeable c : EnergyCompat.list(p, chargeRate)) {
			if (slot >= 0 && c.slot() != slot) {
				continue;
			}
			long missing = c.capacity() - c.amount();
			long affordable = (long) Math.floor(Matter.balance(p) + 1e-9) * chargeRate;
			long want = Math.min(missing, affordable);
			if (want <= 0) {
				continue;
			}
			long in = EnergyCompat.charge(p, c.slot(), want);
			int price = (int) ((in + chargeRate - 1) / chargeRate);
			Matter.add(p, -price);
			energy += in;
			cost += price;
		}
		p.containerMenu.broadcastChanges();
		if (energy > 0) {
			p.displayClientMessage(Component.literal("Charged " + String.format("%,d", energy) + " energy for " + cost + " Strange Matter")
					.withStyle(ChatFormatting.AQUA), true);
			p.playNotifySound(SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.5f, 1.6f);
		} else {
			p.displayClientMessage(Component.literal(Matter.balance(p) < 1 ? "Not enough Strange Matter" : "Nothing to charge").withStyle(ChatFormatting.GRAY), true);
		}
		sendCharges(p);
	}

	// ------------------------------------------------------------------ editing (ops)

	static void edit(ServerPlayer p, ShopPayloads.Edit e) {
		if (!p.hasPermissions(2)) {
			sendCatalog(p);
			return;
		}
		Section sec = visibleSection(e.section());
		switch (e.action()) {
			case ShopPayloads.EDIT_SET -> {
				Entry en = sec == null ? null : visibleEntry(sec, e.index());
				if (en != null) {
					en.price = Math.max(0, Math.min(MAX_PRICE, e.price()));
					en.stack.setCount(Math.max(1, Math.min(en.stack.getMaxStackSize(), e.count())));
				}
			}
			case ShopPayloads.EDIT_REMOVE -> {
				Entry en = sec == null ? null : visibleEntry(sec, e.index());
				if (en != null) {
					sec.entries.remove(en);
				}
			}
			case ShopPayloads.EDIT_MOVE -> {
				Entry en = sec == null ? null : visibleEntry(sec, e.index());
				if (en != null) {
					int from = sec.entries.indexOf(en);
					int to = Math.max(0, Math.min(sec.entries.size() - 1, from + Integer.signum(e.count())));
					sec.entries.remove(from);
					sec.entries.add(to, en);
				}
			}
			case ShopPayloads.EDIT_ADD_HELD -> {
				ItemStack held = p.getMainHandItem();
				if (sec == null || sec.charging || held.isEmpty()) {
					p.displayClientMessage(Component.literal("Hold the item you want to add in your main hand").withStyle(ChatFormatting.RED), true);
				} else {
					Entry en = new Entry();
					en.stack = held.copy();
					en.price = e.price() > 0 ? e.price() : (int) Math.max(1, Math.ceil(Matter.value(held) * 2));
					sec.entries.add(en);
				}
			}
			case ShopPayloads.EDIT_ADD_SECTION -> {
				String name = e.text().trim();
				if (!name.isEmpty() && sections.size() < 32) {
					Section ns = new Section();
					ns.name = name.length() > 24 ? name.substring(0, 24) : name;
					ItemStack held = p.getMainHandItem();
					ns.icon = held.isEmpty() ? "minecraft:chest" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
					sections.add(ns);
				}
			}
			case ShopPayloads.EDIT_REMOVE_SECTION -> {
				if (sec != null) {
					sections.remove(sec);
				}
			}
			default -> {
			}
		}
		save();
		broadcastCatalog();
	}

	/** /bubble shop reload */
	static void reload() {
		if (server != null) {
			load();
			broadcastCatalog();
		}
	}
}
