package moonahmc.bubble;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * A case you hold: quests (run goals) and /bubble givecase hand these out. Right-click opens one with the usual
 * spinning reel; sneak + right-click opens up to 5 from the stack. Which case it is lives in the item's custom data
 * ("bubble_case"), so one item type covers every case in config/bubble-cases.json, tinted with the case's colour.
 */
public final class CaseItem extends Item {
	static final String KEY = "bubble_case";

	public CaseItem(Properties properties) {
		super(properties);
	}

	/** A stack of `count` cases of this kind. */
	public static ItemStack of(String caseId, int count) {
		ItemStack s = new ItemStack(ModContent.CASE.get(), Math.max(1, count));
		CompoundTag tag = new CompoundTag();
		tag.putString(KEY, caseId);
		tag.putInt("color", Gamble.caseColor(caseId));
		String style = Gamble.caseStyle(caseId);
		if (!style.isEmpty()) {
			tag.putString("style", style); // its name moves on the client (client Rarity)
		}
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		s.set(DataComponents.ITEM_NAME, Component.literal(Gamble.caseName(caseId)).withStyle(s2 -> s2.withColor(Gamble.caseColor(caseId))));
		s.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Right-click to open").withStyle(s2 -> s2.withItalic(false).withColor(ChatFormatting.GRAY)),
				Component.literal("Sneak + right-click: open up to 5").withStyle(s2 -> s2.withItalic(false).withColor(ChatFormatting.DARK_GRAY)))));
		return s;
	}

	static String caseId(ItemStack s) {
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return d == null ? "" : d.copyTag().getString(KEY);
	}

	/** The tint for the coloured band (client item colours). */
	public static int color(ItemStack s) {
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return d != null && d.contains("color") ? d.copyTag().getInt("color") : 0xE4AE39;
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (player instanceof ServerPlayer sp) {
			int n = player.isShiftKeyDown() ? Math.min(5, stack.getCount()) : 1;
			if (Gamble.open(sp, caseId(stack), 0, -1, -1, n)) {
				stack.shrink(n);
			}
		}
		return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
	}
}
