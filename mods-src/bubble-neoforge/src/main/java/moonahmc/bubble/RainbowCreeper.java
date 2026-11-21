package moonahmc.bubble;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.Level;

/**
 * A rainbow creeper: absurdly fast (100× a normal creeper by default, set in the pre-game tab), and its explosion is all
 * show: the usual bang and smoke, no broken blocks, and one heart of damage to anything close.
 */
public class RainbowCreeper extends Creeper {
	public RainbowCreeper(EntityType<? extends Creeper> type, Level level) {
		super(type, level);
		CreeperFuse.set(this, 1); // no hiss: it goes off the moment it reaches you
	}

	/** Knows where you are from the moment it spawns: always hunting the nearest survival player within 128 blocks. */
	private void hunt() {
		net.minecraft.world.entity.LivingEntity t = getTarget();
		if (t == null || !t.isAlive() || (t instanceof net.minecraft.world.entity.player.Player p && (p.isCreative() || p.isSpectator()))) {
			net.minecraft.world.entity.player.Player near = level().getNearestPlayer(getX(), getY(), getZ(), 128,
					e -> e instanceof net.minecraft.world.entity.player.Player p && !p.isCreative() && !p.isSpectator() && p.isAlive());
			setTarget(near);
		}
		t = getTarget();
		if (t != null && distanceToSqr(t) < 9 && hasLineOfSight(t)) {
			ignite();
		}
	}

	@Override
	public void tick() {
		if (!level().isClientSide && isAlive()) {
			hunt();
		}
		// Speed follows the setting, so changing it in the Bubble menu affects creepers that are already out.
		if (!level().isClientSide && tickCount % 20 == 1) {
			AttributeInstance speed = getAttribute(Attributes.MOVEMENT_SPEED);
			double want = 0.25 * Pregame.rainbowSpeed();
			if (speed != null && speed.getBaseValue() != want) {
				speed.setBaseValue(want);
			}
		}
		super.tick();
	}

	/** Called instead of the vanilla explosion (see Pregame's ExplosionEvent listener). */
	void popHarmlessly() {
		if (!(level() instanceof ServerLevel level)) {
			return;
		}
		level.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 4.0f,
				(1.0f + (random.nextFloat() - random.nextFloat()) * 0.2f) * 0.7f);
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY() + 0.5, getZ(), 1, 0, 0, 0, 0);
		level.sendParticles(ParticleTypes.FIREWORK, getX(), getY() + 0.8, getZ(), 60, 0.6, 0.6, 0.6, 0.25);
		for (LivingEntity near : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(4.0), e -> e != this && e.isAlive())) {
			if (near.distanceToSqr(this) <= 16) {
				near.hurt(damageSources().explosion(this, this), 2.0f); // one heart
			}
		}
	}
}
