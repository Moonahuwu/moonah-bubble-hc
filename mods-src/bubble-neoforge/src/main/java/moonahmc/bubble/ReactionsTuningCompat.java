package moonahmc.bubble;

import dev.leo.ragdollreactions.config.ReactionSettings;

/** Ragdoll Reactions' live settings (only called when it's installed). */
final class ReactionsTuningCompat {
	/** The mod's own launch multipliers, which "fling" scales. */
	private static final double HIT_LAUNCH = 1.5;
	private static final double CRASH_LAUNCH = 3.0;
	private static final double EXPLOSION_LAUNCH = 15.0;

	private ReactionsTuningCompat() {
	}

	/** What the mod is actually using right now. */
	static String describe() {
		var tr = ReactionSettings.triggers();
		return "reactions " + (ReactionSettings.enabled() ? "on" : "off") + ", impact " + tr.impact().minVelocityDelta() + " m/s, hit "
				+ tr.hit().minDamage() + " (x" + tr.hit().launchMultiplier() + "), fall " + tr.fall().minDamage() + ", blast x"
				+ tr.vanillaExplosions().launchMultiplier() + ", cooldown " + ReactionSettings.general().launch().cooldownTicks()
				+ " ticks, mobs " + (ReactionSettings.mobs().enabled() ? "on" : "off");
	}

	static void apply(RagdollTuning.Tune t) {
		ReactionSettings.setEnabled(t.reactions);
		var tr = ReactionSettings.triggers();
		tr.impact().setMinVelocityDelta(t.impactSpeed);
		tr.hit().setMinDamage(t.hitDamage);
		tr.hit().setLaunchMultiplier(HIT_LAUNCH * t.fling);
		tr.fall().setMinDamage(t.fallDamage);
		tr.crash().setMinDamage(t.fallDamage);
		tr.crash().setLaunchMultiplier(CRASH_LAUNCH * t.fling);
		tr.vanillaExplosions().setLaunchMultiplier(EXPLOSION_LAUNCH * t.fling);
		tr.cannonExplosions().setLaunchMultiplier(EXPLOSION_LAUNCH * t.fling);
		ReactionSettings.general().launch().setCooldownTicks(t.cooldownSeconds * 20);
		ReactionSettings.mobs().setEnabled(t.mobsReact);
	}
}
