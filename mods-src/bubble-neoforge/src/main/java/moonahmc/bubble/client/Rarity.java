package moonahmc.bubble.client;

import moonahmc.bubble.Gamble;
import moonahmc.bubble.NameStyles;
import net.minecraft.Util;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.List;

/**
 * Prism touches (animated where Prism is installed, still colours where it isn't):
 * <ul>
 * <li>Epic and Legendary unboxes: the prize's name shimmers / flows in the case screen, the chat broadcast and its
 * tooltip; "★ Factory New" shimmers too.</li>
 * <li>Cases with a name style (case editor): their name moves in the shop and on the case item.</li>
 * <li>Apotheosis elites: their name pulses above their head.</li>
 * <li>Shop rows and the sell slot: item names in the item's own colour (Prism's item colours: rarity, name colour).</li>
 * <li>{@link #pulse}: HUD colours that breathe (bleed-out timer, down cam title, Strange Matter pop-ups).</li>
 * </ul>
 */
public final class Rarity {
	static final NameStyles.Spec EPIC = new NameStyles.Spec("tier_epic", NameStyles.GLOW, new int[] {0xEB4B4B}, 1.8f);
	static final NameStyles.Spec LEGENDARY = new NameStyles.Spec("tier_legendary", NameStyles.ANIMATED,
			new int[] {0xE4AE39, 0xFFE27A, 0xFF8A2A}, 2.4f);
	static final NameStyles.Spec FACTORY_NEW = new NameStyles.Spec("factory_new", NameStyles.GLOW, new int[] {0xFFAA00}, 1.6f);
	static final String FACTORY_NEW_TEXT = "★ Factory New";
	private static boolean prism;

	private Rarity() {
	}

	static void register() {
		prism = ModList.get().isLoaded("prism");
		NeoForge.EVENT_BUS.addListener((ItemTooltipEvent e) -> {
			List<Component> lines = e.getToolTip();
			if (lines.isEmpty()) {
				return;
			}
			ItemStack s = e.getItemStack();
			if (tier(s) >= 3 || caseStyle(s) != null) {
				Component name = itemName(s);
				lines.set(0, name);
			}
			for (int i = 1; i < lines.size(); i++) {
				if (lines.get(i).getString().equals(FACTORY_NEW_TEXT)) {
					lines.set(i, FancyNames.build(FACTORY_NEW_TEXT, FACTORY_NEW).withStyle(st -> st.withBold(true).withItalic(false)));
				}
			}
		});
		NeoForge.EVENT_BUS.addListener((RenderNameTagEvent e) -> {
			if (e.getEntity() instanceof Mob m && m.hasCustomName()) {
				Component c = e.getContent();
				Component styled = FancyNames.restyle(c);
				if (!styled.equals(c)) {
					e.setContent(styled);
				}
			}
		});
	}

	private static CompoundTag data(ItemStack s) {
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return d == null ? null : d.copyTag();
	}

	/** The case tier a prize came from (only stamped on Epic+ gear that doesn't stack), or -1. */
	static int tier(ItemStack s) {
		CompoundTag t = data(s);
		return t != null && t.contains("bubble_tier") ? t.getInt("bubble_tier") : -1;
	}

	/** A case's name style (case editor), on a case item or a shop case offer. */
	static NameStyles.Spec caseStyle(ItemStack s) {
		CompoundTag t = data(s);
		if (t == null) {
			return null;
		}
		String id = t.contains("bubble_case_style") ? t.getString("bubble_case_style") : t.getString("style");
		return id.isEmpty() ? null : spec(id);
	}

	/** Name styles plus the tier ones. */
	static NameStyles.Spec spec(String id) {
		return switch (id) {
			case "tier_epic" -> EPIC;
			case "tier_legendary" -> LEGENDARY;
			case "factory_new" -> FACTORY_NEW;
			default -> NameStyles.spec(id);
		};
	}

	/** A prize's name in its tier colour: shimmering for Epic, flowing gold for Legendary. */
	static MutableComponent tierName(String text, int tier) {
		if (tier >= 4) {
			return FancyNames.build(text, LEGENDARY);
		}
		if (tier == 3) {
			return FancyNames.build(text, EPIC);
		}
		int t = Math.max(0, Math.min(Gamble.TIER_COLORS.length - 1, tier));
		return Component.literal(text).withStyle(st -> st.withColor(Gamble.TIER_COLORS[t] & 0xFFFFFF));
	}

	/** How an item's name is shown in the shop, the sell slot and tooltips. */
	static Component itemName(ItemStack s) {
		NameStyles.Spec style = caseStyle(s);
		if (style != null) {
			return FancyNames.build(s.getHoverName().getString(), style);
		}
		int tier = tier(s);
		if (tier >= 3) {
			return tierName(s.getHoverName().getString(), tier);
		}
		if (prism) {
			try {
				TextColor c = PrismNames.itemColor(s);
				if (c != null) {
					return Component.literal(s.getHoverName().getString()).withStyle(st -> st.withColor(c));
				}
			} catch (Throwable t) {
				prism = false;
			}
		}
		return s.getHoverName();
	}

	/** `a` and `b` taking turns, once every `seconds` (for things drawn every frame). */
	static int pulse(int a, int b, float seconds) {
		double t = (Util.getMillis() % (long) (seconds * 1000)) / (seconds * 1000.0);
		float f = (float) (0.5 - 0.5 * Math.cos(t * Math.PI * 2));
		return (a & 0xFF000000) | NameStyles.mix(a & 0xFFFFFF, b & 0xFFFFFF, f);
	}

	/** Elite names: the whole name breathes between its colour and white. */
	static TextColor elitePulse(int base) {
		if (prism) {
			try {
				return PrismNames.pulse(base, 1.6f);
			} catch (Throwable t) {
				prism = false;
			}
		}
		return TextColor.fromRgb(pulse(base, 0xFFFFFF, 1.6f) & 0xFFFFFF);
	}
}
