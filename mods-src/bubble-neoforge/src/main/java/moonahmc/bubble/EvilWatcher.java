package moonahmc.bubble;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

/**
 * The Evil Watcher: rarer, with a rainbow outline and a rainbow aura. Looking anywhere at it (not just its eyes) kills
 * you, even through blocks. A carved pumpkin is the only thing that protects you.
 */
public class EvilWatcher extends Watcher {
	static final ResourceKey<DamageType> STARE = ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "evil_watcher"));

	public EvilWatcher(EntityType<? extends EnderMan> type, Level level) {
		super(type, level);
	}

	@Override
	protected ResourceKey<DamageType> stareType() {
		return STARE;
	}

	@Override
	protected int pearls() {
		return 4 + random.nextInt(3);
	}

	/** The whole body, padded. */
	@Override
	protected AABB lookBox() {
		return getBoundingBox().inflate(0.35);
	}

	@Override
	protected boolean seesThroughWalls() {
		return true;
	}

	private int rainbow(float offset) {
		float hue = ((level().getGameTime() % 60) / 60f + offset) % 1f;
		return Mth.hsvToRgb(hue, 0.85f, 1f);
	}

	@Override
	public void aiStep() {
		super.aiStep();
		if (level().isClientSide && isAlive()) {
			// rainbow aura: coloured dust swirling around the body
			for (int i = 0; i < 3; i++) {
				int c = rainbow(random.nextFloat() * 0.3f);
				double a = random.nextDouble() * Math.PI * 2;
				double r = 0.6 + random.nextDouble() * 0.5;
				level().addParticle(new DustParticleOptions(new Vector3f(((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f), 1.2f),
						getX() + Math.cos(a) * r, getY() + random.nextDouble() * getBbHeight(), getZ() + Math.sin(a) * r, 0, 0.02, 0);
			}
		}
	}

	/** The outline cycles through the rainbow. */
	@Override
	public int getTeamColor() {
		return rainbow(0);
	}
}
