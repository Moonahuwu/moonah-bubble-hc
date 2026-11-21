package moonahmc.bubble;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;

/**
 * Looks and acts exactly like a zombie (it's even called "Zombie"). Kill it and it bursts into 100 tiny 1 HP zombies,
 * each worth 1 Strange Matter to whoever kills it.
 */
public final class SwarmZombie extends Zombie {
	public static final int SWARM = 100;

	public SwarmZombie(EntityType<? extends Zombie> type, Level level) {
		super(type, level);
	}

	@Override
	protected boolean convertsInWater() {
		return false; // stays a swarm zombie instead of turning into a drowned
	}

	@Override
	public void die(DamageSource source) {
		boolean first = !isRemoved() && !dead;
		super.die(source);
		if (first && level() instanceof ServerLevel level) {
			burst(level);
		}
	}

	private void burst(ServerLevel level) {
		for (int i = 0; i < SWARM; i++) {
			Swarmling s = ModContent.SWARMLING.get().create(level);
			if (s == null) {
				return;
			}
			s.moveTo(getX(), getY() + 0.4, getZ(), level.random.nextFloat() * 360, 0);
			double a = level.random.nextDouble() * Math.PI * 2;
			double v = 0.15 + level.random.nextDouble() * 0.35;
			s.setDeltaMovement(Math.cos(a) * v, 0.25 + level.random.nextDouble() * 0.35, Math.sin(a) * v);
			if (getTarget() != null) {
				s.setTarget(getTarget());
			}
			level.addFreshEntity(s);
		}
		level.sendParticles(ParticleTypes.POOF, getX(), getY() + 1, getZ(), 40, 0.6, 0.6, 0.6, 0.08);
		level.playSound(null, getX(), getY(), getZ(), SoundEvents.ZOMBIE_INFECT, SoundSource.HOSTILE, 1.6f, 0.6f);
		level.playSound(null, getX(), getY(), getZ(), SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.4f, 0.5f);
	}
}
