package moonahmc.bubble;

import dev.shadowsoffire.apotheosis.loot.LootCategory;
import dev.shadowsoffire.apotheosis.loot.LootController;
import dev.shadowsoffire.apotheosis.loot.LootRarity;
import dev.shadowsoffire.apotheosis.loot.RarityRegistry;
import dev.shadowsoffire.apotheosis.tiers.GenContext;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The shop's Apothic Roll: picks a random tool or weapon in your inventory and gives it a random Apotheosis rarity,
 * with fresh affixes. Only loaded when Apotheosis is installed.
 */
final class ApothRoll {
	/** How likely each rarity is, common to mythic (anything after that: 1). */
	private static final int[] WEIGHTS = {40, 30, 18, 9, 3};

	private ApothRoll() {
	}

	/** Returns false (and tells the player) if there was nothing to roll. minTier: 0 = any, 2 = rare+, 3 = epic+. */
	static boolean roll(ServerPlayer p, int minTier) {
		Inventory inv = p.getInventory();
		List<Integer> slots = new ArrayList<>();
		for (int i = 0; i < inv.items.size(); i++) {
			ItemStack stack = inv.items.get(i);
			if (!stack.isEmpty() && stack.getCount() == 1 && eligible(stack)) {
				slots.add(i);
			}
		}
		List<LootRarity> rarities = RarityRegistry.getSortedRarities();
		if (slots.isEmpty() || rarities.isEmpty()) {
			p.displayClientMessage(Component.literal("You need a tool or weapon in your inventory to roll.").withStyle(ChatFormatting.RED), true);
			p.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.6f, 1.2f);
			return false;
		}
		int slot = slots.get(p.getRandom().nextInt(slots.size()));
		ItemStack old = inv.items.get(slot);
		LootRarity rarity = pick(p, rarities, Math.min(minTier, rarities.size() - 1));
		LootCategory cat = LootCategory.forItem(old);
		ItemStack rolled = LootController.createLootItem(old.copy(), cat, rarity, GenContext.forPlayer(p));
		inv.setItem(slot, rolled);
		Goals.custom("apothic_roll");
		p.displayClientMessage(Component.literal("✦ ").withStyle(ChatFormatting.GOLD).append(old.getHoverName())
				.append(Component.literal(" is now ").withStyle(ChatFormatting.GRAY)).append(rarity.toComponent())
				.append(Component.literal("!").withStyle(ChatFormatting.GRAY)), false);
		p.playNotifySound(SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1.0f, 1.0f);
		p.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5f, 1.4f + 0.1f * rarities.indexOf(rarity));
		return true;
	}

	/** Gives a brand-new random Apotheosis item (gear with affixes). For lucky blocks: /bubble apothloot <player> [min rarity]. */
	static boolean giveRandom(ServerPlayer p, int minTier) {
		List<LootRarity> rarities = RarityRegistry.getSortedRarities();
		if (rarities.isEmpty()) {
			return false;
		}
		LootRarity rarity = pick(p, rarities, Math.min(minTier, rarities.size() - 1));
		ItemStack stack = LootController.createRandomLootItem(GenContext.forPlayer(p), rarity);
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		if (!p.getInventory().add(stack)) {
			p.drop(stack, false);
		}
		return true;
	}

	static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> command() {
		var node = net.minecraft.commands.Commands.literal("apothloot").requires(s -> s.hasPermission(2));
		var target = net.minecraft.commands.Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
				.executes(c -> giveRandom(net.minecraft.commands.arguments.EntityArgument.getPlayer(c, "player"), 0) ? 1 : 0);
		String[] names = {"common", "uncommon", "rare", "epic", "mythic"};
		for (int i = 0; i < names.length; i++) {
			int tier = i;
			target.then(net.minecraft.commands.Commands.literal(names[i])
					.executes(c -> giveRandom(net.minecraft.commands.arguments.EntityArgument.getPlayer(c, "player"), tier) ? 1 : 0));
		}
		return node.then(target);
	}

	/**
	 * This item with a random Apotheosis rarity and affixes (armour too), or null if Apotheosis can't affix it. For
	 * case prizes (Condition). Uses the same rarity odds as the Apothic Roll, from `minTier` up.
	 */
	static ItemStack affix(ServerPlayer p, ItemStack stack, int minTier) {
		try {
			LootCategory cat = LootCategory.forItem(stack);
			if (cat == null || "none".equals(cat.getKey() == null ? "none" : cat.getKey().getPath())) {
				return null;
			}
			List<LootRarity> rarities = RarityRegistry.getSortedRarities();
			if (rarities.isEmpty()) {
				return null;
			}
			LootRarity rarity = pick(p, rarities, Math.min(minTier, rarities.size() - 1));
			return LootController.createLootItem(stack.copy(), cat, rarity, GenContext.forPlayer(p));
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Tools and weapons (anything Apotheosis can give affixes to, except armor). */
	private static boolean eligible(ItemStack stack) {
		try {
			LootCategory cat = LootCategory.forItem(stack);
			return cat != null && !cat.isArmor() && !"none".equals(cat.getKey() == null ? "none" : cat.getKey().getPath());
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static LootRarity pick(ServerPlayer p, List<LootRarity> rarities, int minTier) {
		int total = 0;
		for (int i = minTier; i < rarities.size(); i++) {
			total += i < WEIGHTS.length ? WEIGHTS[i] : 1;
		}
		int r = p.getRandom().nextInt(Math.max(1, total));
		for (int i = minTier; i < rarities.size(); i++) {
			r -= i < WEIGHTS.length ? WEIGHTS[i] : 1;
			if (r < 0) {
				return rarities.get(i);
			}
		}
		return rarities.get(rarities.size() - 1);
	}
}
