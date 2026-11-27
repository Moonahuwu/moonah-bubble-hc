package moonahmc.bubble;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/** Rare, from cases and bosses: right-click to open the shop just for you for a while (shopKeySeconds, 60). */
public final class ShopKeyItem extends Item {
	public ShopKeyItem(Properties props) {
		super(props);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (level.isClientSide || !(player instanceof ServerPlayer p)) {
			return InteractionResultHolder.success(stack);
		}
		String why = ShopWindows.useKey(p);
		if (why != null) {
			p.displayClientMessage(Component.literal(why).withStyle(ChatFormatting.YELLOW), true);
			return InteractionResultHolder.fail(stack);
		}
		stack.consume(1, p);
		return InteractionResultHolder.consume(stack);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
		lines.add(Component.literal("Right-click to open the shop for you").withStyle(ChatFormatting.AQUA));
		lines.add(Component.literal("Only does anything while the shop is closed").withStyle(ChatFormatting.GRAY));
	}
}
