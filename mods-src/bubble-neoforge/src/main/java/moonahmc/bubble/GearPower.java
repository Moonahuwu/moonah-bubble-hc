package moonahmc.bubble;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ops: /bubble gearpower <mod>. Every item from a mod with its melee damage, attack speed, armour, durability, rarity and
 * what it sells for, strongest first, so new mods' gear can be put in the right case tier. Also written to the log.
 */
final class GearPower {
	private GearPower() {
	}

	private static double sum(ItemAttributeModifiers mods, Holder<Attribute> attr) {
		double v = 0;
		for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
			if (e.attribute().equals(attr) && e.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
				v += e.modifier().amount();
			}
		}
		return v;
	}

	/** `ns` ending in "+" lists every item from the mod, not just gear. */
	static int report(CommandSourceStack src, String arg) {
		boolean all = arg.endsWith("+");
		String ns = all ? arg.substring(0, arg.length() - 1) : arg;
		record Row(String id, double dmg, double speed, double armor, double tough, int dur, String rarity, double worth) {
		}
		List<Row> rows = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) {
			ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
			if (!id.getNamespace().equals(ns)) {
				continue;
			}
			ItemStack stack = new ItemStack(item);
			ItemAttributeModifiers mods = stack.getAttributeModifiers();
			double dmg = sum(mods, Attributes.ATTACK_DAMAGE);
			double armor = sum(mods, Attributes.ARMOR);
			int dur = stack.getMaxDamage();
			if (!all && dmg == 0 && armor == 0 && dur == 0) {
				continue; // not gear
			}
			rows.add(new Row(id.getPath(), dmg == 0 ? 0 : dmg + 1, dmg == 0 ? 0 : 4 + sum(mods, Attributes.ATTACK_SPEED), armor,
					sum(mods, Attributes.ARMOR_TOUGHNESS), dur, stack.getRarity().name().toLowerCase(Locale.ROOT), Matter.value(stack)));
		}
		rows.sort((a, b) -> Double.compare(b.dmg * Math.max(0.5, b.speed) + b.armor * 2, a.dmg * Math.max(0.5, a.speed) + a.armor * 2));
		src.sendSuccess(() -> Component.literal("Gear in " + ns + ": " + rows.size() + " (damage, speed, armour/toughness, durability, rarity, sells for)"), false);
		for (Row r : rows) {
			String line = String.format(Locale.ROOT, "gearpower %s:%s dmg=%.1f spd=%.2f dps=%.1f armor=%.0f/%.0f dur=%d rarity=%s worth=%.0f",
					ns, r.id, r.dmg, r.speed, r.dmg * r.speed, r.armor, r.tough, r.dur, r.rarity, r.worth);
			Bubble.LOG.info(line);
			src.sendSuccess(() -> Component.literal(line), false);
		}
		return rows.size();
	}
}
