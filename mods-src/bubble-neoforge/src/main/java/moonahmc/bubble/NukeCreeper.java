package moonahmc.bubble;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * A nuke creeper: very slow, tanky, with a long fuse, and when it goes off it sets off the same explosion as Oritech's
 * nuke (the "Manhattan" one). Without Oritech it's a very big TNT blast instead.
 */
public class NukeCreeper extends Creeper {
	/** Movement speed compared to a normal creeper. */
	static final double SPEED = 0.4;
	/** Chance a new nuke creeper is the unstable kind (no fuse at all). */
	static final double UNSTABLE_CHANCE = 0.01;
	private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> UNSTABLE =
			net.minecraft.network.syncher.SynchedEntityData.defineId(NukeCreeper.class, net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);
	private static final ResourceLocation NUKE_EXPLOSION = ResourceLocation.fromNamespaceAndPath("oritech", "reactor_explosion_large");

	public NukeCreeper(EntityType<? extends Creeper> type, Level level) {
		super(type, level);
		// A long fuse: about 4 seconds of hissing before it goes off.
		CreeperFuse.set(this, 80);
	}

	@Override
	protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(UNSTABLE, false);
	}

	/** The 1% unstable kind: no fuse, it goes off the instant it reaches you. Pulses red. */
	public boolean isUnstable() {
		return entityData.get(UNSTABLE);
	}

	void setUnstable(boolean unstable) {
		entityData.set(UNSTABLE, unstable);
		CreeperFuse.set(this, unstable ? 1 : 80);
	}

	@Override
	public net.minecraft.world.entity.SpawnGroupData finalizeSpawn(net.minecraft.world.level.ServerLevelAccessor level,
			net.minecraft.world.DifficultyInstance difficulty, net.minecraft.world.entity.MobSpawnType reason,
			net.minecraft.world.entity.SpawnGroupData data) {
		if (level.getRandom().nextDouble() < UNSTABLE_CHANCE) {
			setUnstable(true);
		}
		return super.finalizeSpawn(level, difficulty, reason, data);
	}

	@Override
	public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.putBoolean("BubbleUnstable", isUnstable());
	}

	@Override
	public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		if (tag.getBoolean("BubbleUnstable")) {
			setUnstable(true);
		}
	}

	@Override
	public void tick() {
		if (!level().isClientSide && isUnstable() && getTarget() != null && distanceToSqr(getTarget()) < 9 && hasLineOfSight(getTarget())) {
			ignite();
		}
		super.tick();
	}

	/** Called instead of the vanilla explosion (see Pregame's ExplosionEvent listener). */
	void detonate() {
		if (level() instanceof ServerLevel level) {
			nuke(level, blockPosition(), "A nuke creeper");
		}
	}

	/** Oritech's Manhattan-style nuke at `pos` (a 24-power TNT blast without Oritech). Also /bubble nuke and Lucky Blocks. */
	static void nuke(ServerLevel level, BlockPos pos, String what) {
		Block nuke = BuiltInRegistries.BLOCK.getOptional(NUKE_EXPLOSION).orElse(null);
		if (nuke != null) {
			level.setBlockAndUpdate(pos, nuke.defaultBlockState());
			level.playSound(null, pos, SoundEvents.TNT_PRIMED, SoundSource.HOSTILE, 1.0f, 1.0f);
		} else {
			level.explode(null, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 24.0f, true, Level.ExplosionInteraction.TNT);
		}
		Bubble.LOG.info("{} went off at {} {} {} in {}", what, pos.getX(), pos.getY(), pos.getZ(), level.dimension().location());
	}
}
