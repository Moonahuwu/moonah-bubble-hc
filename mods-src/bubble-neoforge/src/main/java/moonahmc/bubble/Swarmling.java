package moonahmc.bubble;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * One of the 100 tiny zombies from a swarm zombie: half a heart of health, a third of the size, exactly half a heart per
 * hit on any difficulty, doesn't burn in the sun, takes no fall or cramming damage, drops nothing, and pays 1 Strange
 * Matter to the player who kills it. Health and damage boosts from other mods (Apotheosis world tiers) are taken off,
 * and so are armour and potion effects: any hit kills it, whatever armour or Resistance it picked up.
 */
public final class Swarmling extends Zombie {
	public Swarmling(EntityType<? extends Zombie> type, Level level) {
		super(type, level);
		xpReward = 1;
		setCanPickUpLoot(false);
	}

	@Override
	public void tick() {
		// Other mods can add boosts after it spawns (world tiers, mob effects), so this is checked every second
		if (!level().isClientSide && tickCount % 20 == 1) {
			trim();
		}
		super.tick();
	}

	private void trim() {
		var hp = getAttribute(Attributes.MAX_HEALTH);
		if (hp != null && (hp.getBaseValue() != 1 || !hp.getModifiers().isEmpty())) {
			hp.removeModifiers();
			hp.setBaseValue(1);
		}
		var dmg = getAttribute(Attributes.ATTACK_DAMAGE);
		if (dmg != null && (dmg.getBaseValue() != 1 || !dmg.getModifiers().isEmpty())) {
			dmg.removeModifiers();
			dmg.setBaseValue(1);
		}
		for (var a : new net.minecraft.core.Holder[] {Attributes.ARMOR, Attributes.ARMOR_TOUGHNESS}) {
			@SuppressWarnings("unchecked")
			var inst = getAttribute((net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute>) a);
			if (inst != null && (inst.getBaseValue() != 0 || !inst.getModifiers().isEmpty())) {
				inst.removeModifiers();
				inst.setBaseValue(0);
			}
		}
		if (!getActiveEffects().isEmpty()) {
			removeAllEffects();
		}
		setAbsorptionAmount(0);
		if (getHealth() > 1) {
			setHealth(1);
		}
	}

	/** One hit kills, whatever armour, Resistance or protection it picked up: if a real hit leaves it alive, it dies anyway
	 *  (NeoForge counts armour and Resistance as reductions that can't be made negative, so this is done after the hit). */
	@Override
	public boolean hurt(DamageSource source, float amount) {
		boolean hit = super.hurt(source, amount);
		if (hit && amount > 0 && !level().isClientSide && isAlive() && !isRemoved()) {
			setHealth(0);
			die(source);
		}
		return hit;
	}

	/** Exactly half a heart (before armour): players take 1.5x mob damage on Hard, so hit for 2/3 there. */
	@Override
	public boolean doHurtTarget(Entity target) {
		float amount = level().getDifficulty() == Difficulty.HARD ? 2f / 3f : 1f;
		boolean hit = target.hurt(damageSources().mobAttack(this), amount);
		if (hit) {
			setLastHurtMob(target);
		}
		return hit;
	}

	@Override
	protected boolean convertsInWater() {
		return false;
	}

	@Override
	public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
		return false; // tiny: lands softly (1 HP would make every burst on a ledge a waste)
	}

	@Override
	public boolean isInvulnerableTo(DamageSource source) {
		// 100 of them start in one spot: vanilla entity cramming would crush most of them instantly
		return source.is(net.minecraft.world.damagesource.DamageTypes.CRAMMING) || super.isInvulnerableTo(source);
	}

	@Override
	protected boolean isSunSensitive() {
		return false;
	}

	@Override
	protected ResourceKey<LootTable> getDefaultLootTable() {
		return BuiltInLootTables.EMPTY;
	}

	@Override
	public void die(DamageSource source) {
		boolean first = !isRemoved() && !dead;
		super.die(source);
		if (first && !level().isClientSide && source.getEntity() instanceof ServerPlayer p) {
			Matter.earn(p, 1);
		}
	}
}
