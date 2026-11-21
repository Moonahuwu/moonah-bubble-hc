package moonahmc.bubble;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Watcher: an enderman with a red outline. Look it in the eyes and you die on the spot (a carved pumpkin on your
 * head protects you, like with normal endermen). Otherwise it behaves like any enderman.
 *
 * The kill is its own damage type (data/bubble/damage_type) that goes straight through armour, shields, Protection and
 * Resistance, so your armour isn't shredded by the huge hit. A Totem of Undying still saves you.
 */
public class Watcher extends EnderMan {
	protected static final double RANGE = 64;
	/** Kills anyone (max health in this pack is far below this) without the absurd armour damage 100k would do. */
	static final float STARE_DAMAGE = 500f;
	static final ResourceKey<DamageType> STARE = ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "watcher"));

	public Watcher(EntityType<? extends EnderMan> type, Level level) {
		super(type, level);
	}

	protected ResourceKey<DamageType> stareType() {
		return STARE;
	}

	@Override
	public void aiStep() {
		super.aiStep();
		if (level().isClientSide || !isAlive()) {
			return;
		}
		for (Player p : level().players()) {
			if (p instanceof ServerPlayer sp && sp.isAlive() && !sp.isCreative() && !sp.isSpectator()
					&& distanceToSqr(sp) < RANGE * RANGE && staredAtBy(sp)) {
				playSound(SoundEvents.ENDERMAN_SCREAM, 2.0f, 0.6f);
				sp.invulnerableTime = 0;
				var type = level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(stareType());
				sp.hurt(new DamageSource(type, this), STARE_DAMAGE);
			}
		}
	}

	/** The part of it you mustn't look at: the head, padded a little on every side. */
	protected AABB lookBox() {
		AABB box = getBoundingBox();
		return new AABB(box.minX, box.maxY - 0.9, box.minZ, box.maxX, box.maxY, box.maxZ).inflate(0.25);
	}

	/** Walls protect you from a normal Watcher. */
	protected boolean seesThroughWalls() {
		return false;
	}

	/**
	 * True if the player's line of sight crosses the look box at all, even just grazing it (much stricter than a vanilla
	 * enderman, which needs you to look right at its eyes). A carved pumpkin still protects you.
	 */
	private boolean staredAtBy(Player p) {
		ItemStack head = p.getInventory().armor.get(3);
		if (net.neoforged.neoforge.common.CommonHooks.shouldSuppressEnderManAnger(this, p, head)) {
			return false;
		}
		AABB box = lookBox();
		Vec3 eye = p.getEyePosition();
		Vec3 end = eye.add(p.getViewVector(1.0f).scale(RANGE));
		if (box.contains(eye)) {
			return true;
		}
		return box.clip(eye, end).isPresent() && (seesThroughWalls() || p.hasLineOfSight(this));
	}

	/** Red outline for everyone with the mod (drawn by the client, like the Glowing effect). */
	@Override
	public boolean isCurrentlyGlowing() {
		return level().isClientSide() || super.isCurrentlyGlowing();
	}

	@Override
	public int getTeamColor() {
		return 0xFF2020;
	}
}
