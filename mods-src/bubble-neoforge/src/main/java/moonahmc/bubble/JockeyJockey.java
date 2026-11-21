package moonahmc.bubble;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.monster.CaveSpider;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

/**
 * The Jockey Jockey: a spider with a cave spider riding on top. One bite from the big spider gives you every harmful
 * effect in the game (every mod's too) for 10 seconds. Instant ones (like Instant Damage) are left out.
 */
public final class JockeyJockey extends Spider {
	private static final int DEBUFF_TICKS = 200;

	public JockeyJockey(EntityType<? extends Spider> type, Level level) {
		super(type, level);
	}

	@Override
	public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason, SpawnGroupData data) {
		SpawnGroupData out = super.finalizeSpawn(level, difficulty, reason, data);
		getPassengers().forEach(Entity::discard); // no skeleton riders: the seat is taken
		CaveSpider rider = EntityType.CAVE_SPIDER.create(level.getLevel());
		if (rider != null) {
			rider.moveTo(getX(), getY(), getZ(), getYRot(), 0);
			rider.finalizeSpawn(level, difficulty, reason, null);
			rider.startRiding(this, true);
		}
		return out;
	}

	@Override
	public boolean doHurtTarget(Entity target) {
		boolean hit = super.doHurtTarget(target);
		if (hit && target instanceof LivingEntity victim && !level().isClientSide) {
			giveEveryDebuff(victim, this);
		}
		return hit;
	}

	static void giveEveryDebuff(LivingEntity victim, Entity source) {
		for (Holder<MobEffect> effect : BuiltInRegistries.MOB_EFFECT.holders().toList()) {
			MobEffect e = effect.value();
			if (e.getCategory() == MobEffectCategory.HARMFUL && !e.isInstantenous()) {
				try {
					victim.addEffect(new MobEffectInstance(effect, DEBUFF_TICKS, 0), source);
				} catch (RuntimeException ex) {
					Bubble.LOG.debug("Jockey Jockey: couldn't apply {}", effect.getRegisteredName(), ex);
				}
			}
		}
	}
}
