package moonahmc.bubble;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.level.Level;

/**
 * Looks and acts like a normal skeleton, but the moment it can see its target it sprays arrows: 8 a tick, 160 a second,
 * as long as it keeps line of sight. The arrows are light, can't be picked up and vanish when they land, so the server
 * doesn't fill up with them. Damage is still limited by the usual half-second of invulnerability after each hit.
 */
public class GatlingSkeleton extends Skeleton {
	static final int ARROWS_PER_TICK = 8;
	private static final double RANGE = 24;

	public GatlingSkeleton(EntityType<? extends Skeleton> type, Level level) {
		super(type, level);
	}

	@Override
	public void aiStep() {
		super.aiStep();
		if (level().isClientSide || !isAlive()) {
			return;
		}
		LivingEntity target = getTarget();
		if (target == null || !target.isAlive() || distanceToSqr(target) > RANGE * RANGE || !hasLineOfSight(target)
				|| !isHolding(stack -> stack.getItem() instanceof BowItem)) {
			return;
		}
		getLookControl().setLookAt(target, 30f, 30f);
		for (int i = 0; i < ARROWS_PER_TICK; i++) {
			GatlingArrow arrow = new GatlingArrow(level(), this);
			double dx = target.getX() - getX();
			double dy = target.getY(0.3333) - arrow.getY();
			double dz = target.getZ() - getZ();
			double flat = Math.sqrt(dx * dx + dz * dz);
			arrow.shoot(dx, dy + flat * 0.15, dz, 2.4f, 7f);
			level().addFreshEntity(arrow);
		}
		if (tickCount % 2 == 0) {
			playSound(SoundEvents.SKELETON_SHOOT, 0.7f, 1.5f + random.nextFloat() * 0.4f);
		}
	}
}
