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

/** Found in cases and chests: right-click to spin the mid-run wheel (everyone sees it, and the result hits the run). */
public final class WheelSpinItem extends Item {
	public WheelSpinItem(Properties props) {
		super(props);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (level.isClientSide || !(player instanceof ServerPlayer p)) {
			return InteractionResultHolder.success(stack);
		}
		String why = Wheel.spinMidRun(p);
		if (why != null) {
			p.displayClientMessage(Component.literal(why).withStyle(ChatFormatting.RED), true);
			return InteractionResultHolder.fail(stack);
		}
		stack.consume(1, p);
		return InteractionResultHolder.consume(stack);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
		lines.add(Component.literal("Right-click to spin the mid-run wheel").withStyle(ChatFormatting.GOLD));
		lines.add(Component.literal("Everyone sees it, and it can go either way...").withStyle(ChatFormatting.GRAY));
	}
}
