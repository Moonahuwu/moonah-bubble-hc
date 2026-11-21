package moonahmc.bubble;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

/** The gatling skeleton's arrows: can't be picked up, and gone as soon as they land, hit something, or after 2 seconds. */
public class GatlingArrow extends AbstractArrow {
	public GatlingArrow(EntityType<? extends GatlingArrow> type, Level level) {
		super(type, level);
	}

	public GatlingArrow(Level level, LivingEntity owner) {
		super(ModContent.GATLING_ARROW.get(), owner, level, new ItemStack(Items.ARROW), null);
		pickup = Pickup.DISALLOWED;
		setBaseDamage(1.0);
	}

	@Override
	protected ItemStack getDefaultPickupItem() {
		return new ItemStack(Items.ARROW);
	}

	@Override
	public void tick() {
		super.tick();
		if (!level().isClientSide && (inGround || tickCount > 40)) {
			discard();
		}
	}

	@Override
	protected void onHitEntity(EntityHitResult result) {
		super.onHitEntity(result);
		if (!level().isClientSide) {
			discard();
		}
	}
}
