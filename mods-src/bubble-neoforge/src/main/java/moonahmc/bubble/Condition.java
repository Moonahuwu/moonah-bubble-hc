package moonahmc.bubble;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Case prizes that are gear (swords, bows, armour, tools...) can come out better, like a skin's wear in CS:
 * Enchanted (as if from an enchanting table), Apothic (an Apotheosis rarity with affixes), or Factory New (both, with
 * stronger enchantments and at least a rare rarity). Chances are in config/bubble-cases.json, "conditions".
 */
final class Condition {
	static final String ENCHANTED = "Enchanted";
	static final String APOTHIC = "Apothic";
	static final String FACTORY_NEW = "★ Factory New";

	private Condition() {
	}

	static JsonObject defaults() {
		return JsonParser.parseString("""
				{"enchanted": 0.15, "apothic": 0.08, "factoryNew": 0.03}""").getAsJsonObject();
	}

	private static double chance(String key, double def) {
		JsonObject c = Gamble.conditions();
		return c != null && c.has(key) ? Math.max(0, c.get(key).getAsDouble()) : def;
	}

	/** The prize as it comes out: usually as it is, sometimes better. Only gear without a command can change. */
	static Gamble.Drop roll(ServerPlayer p, Gamble.Drop d, RandomSource r) {
		if (d.command() != null && !d.command().isBlank()) {
			return d;
		}
		ItemStack base = d.show();
		boolean enchantable = base.isEnchantable() && base.getCount() == 1;
		boolean apoth = net.neoforged.fml.ModList.get().isLoaded("apotheosis") && base.getCount() == 1;
		if (!enchantable && !apoth) {
			return d;
		}
		double fn = chance("factoryNew", 0.03);
		double ap = chance("apothic", 0.08);
		double en = chance("enchanted", 0.15);
		double x = r.nextDouble();
		ItemStack out = null;
		String label;
		if (x < fn) {
			ItemStack s = apoth ? ApothRoll.affix(p, base, 2) : null;
			if (s == null && !enchantable) {
				return d;
			}
			out = enchant(p, s != null ? s : base.copy(), 35 + r.nextInt(11), r);
			label = FACTORY_NEW;
		} else if (x < fn + ap && apoth) {
			out = ApothRoll.affix(p, base, 0);
			label = APOTHIC;
		} else if (x < fn + ap + en && enchantable) {
			out = enchant(p, base.copy(), 15 + r.nextInt(16), r);
			label = ENCHANTED;
		} else {
			return d;
		}
		if (out == null || out.isEmpty()) {
			return d;
		}
		List<Component> lore = new ArrayList<>();
		ItemLore old = out.get(DataComponents.LORE);
		if (old != null) {
			lore.addAll(old.lines());
		}
		lore.add(tag(label));
		out.set(DataComponents.LORE, new ItemLore(lore));
		return new Gamble.Drop(out, null);
	}

	private static ItemStack enchant(ServerPlayer p, ItemStack s, int level, RandomSource r) {
		var table = p.server.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getTag(EnchantmentTags.IN_ENCHANTING_TABLE);
		ItemStack out = EnchantmentHelper.enchantItem(r, s, level, p.server.registryAccess(), table);
		return out == null || out.isEmpty() ? s : out;
	}

	private static Component tag(String label) {
		ChatFormatting color = label.equals(FACTORY_NEW) ? ChatFormatting.GOLD : label.equals(APOTHIC) ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.AQUA;
		return Component.literal(label).withStyle(s -> s.withItalic(false).withColor(color).withBold(label.equals(FACTORY_NEW)));
	}

	/** "(Enchanted)" / "(Apothic)" / "(★ Factory New)" for chat, or null for a plain prize. */
	static Component label(ItemStack s) {
		String l = find(s);
		return l == null ? null : Component.literal("(").withStyle(ChatFormatting.GRAY).append(tag(l)).append(Component.literal(")").withStyle(ChatFormatting.GRAY));
	}

	static boolean isFactoryNew(ItemStack s) {
		return FACTORY_NEW.equals(find(s));
	}

	private static String find(ItemStack s) {
		ItemLore lore = s.get(DataComponents.LORE);
		if (lore == null || lore.lines().isEmpty()) {
			return null;
		}
		String last = lore.lines().get(lore.lines().size() - 1).getString();
		return last.equals(ENCHANTED) || last.equals(APOTHIC) || last.equals(FACTORY_NEW) ? last : null;
	}
}
