package moonahmc.bubble;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The gatling skeleton's bow (it drops it now and then): hold right-click and it sprays arrows like the skeleton does,
 * 8 a tick. No arrows needed; each tick of firing costs a point of durability (about 19 seconds of fire, more with
 * Unbreaking, and it takes Mending). The arrows are the skeleton's: light, can't be picked up, gone when they land.
 */
public class GatlingBowItem extends BowItem {
	public GatlingBowItem(Properties props) {
		super(props);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		player.startUsingItem(hand); // no arrows needed
		return InteractionResultHolder.consume(player.getItemInHand(hand));
	}

	@Override
	public void onUseTick(Level level, LivingEntity user, ItemStack stack, int remaining) {
		if (level.isClientSide) {
			return;
		}
		for (int i = 0; i < GatlingSkeleton.ARROWS_PER_TICK; i++) {
			GatlingArrow arrow = new GatlingArrow(level, user);
			arrow.shootFromRotation(user, user.getXRot(), user.getYRot(), 0, 2.4f, 7f);
			level.addFreshEntity(arrow);
		}
		if (remaining % 2 == 0) {
			level.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.SKELETON_SHOOT, SoundSource.PLAYERS,
					0.7f, 1.5f + level.random.nextFloat() * 0.4f);
		}
		if (!(user instanceof Player p && p.getAbilities().instabuild)) {
			stack.hurtAndBreak(1, user, user.getUsedItemHand() == InteractionHand.OFF_HAND ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND);
		}
		if (stack.isEmpty()) {
			user.stopUsingItem();
		}
	}

	@Override
	public void releaseUsing(ItemStack stack, Level level, LivingEntity user, int remaining) {
		// it fires while you hold it, not when you let go
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity user) {
		return 72000;
	}

	@Override
	public UseAnim getUseAnimation(ItemStack stack) {
		return UseAnim.BOW;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
		lines.add(Component.literal("Hold right-click to spray arrows").withStyle(ChatFormatting.GRAY));
		lines.add(Component.literal("No arrows needed; wears down as it fires").withStyle(ChatFormatting.DARK_GRAY));
	}
}
