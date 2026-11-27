package moonahmc.bubble;

import dev.leo.sableplayerragdoll.config.RagdollSettings;

/** Sable Ragdolls' live settings (only called when it's installed). */
final class SableTuningCompat {
	private SableTuningCompat() {
	}

	static String describe() {
		return "impact damage " + (RagdollSettings.impactDamageEnabled() ? "x" + RagdollSettings.impactDamageMultiplier() : "off")
				+ ", grab " + (RagdollSettings.grabEnabled() ? "on" : "off") + ", key " + (RagdollSettings.allowManualTrigger() ? "on" : "off")
				+ ", cooldown " + RagdollSettings.cooldownTicks() + " ticks";
	}

	static void apply(RagdollTuning.Tune t) {
		RagdollSettings.setImpactDamageEnabled(t.impactDamage);
		RagdollSettings.setImpactDamageMultiplier(t.impactDamageMultiplier);
		RagdollSettings.setGrabEnabled(t.grab);
		RagdollSettings.setAllowManualTrigger(t.manualKey);
		RagdollSettings.setCooldownTicks(t.cooldownSeconds * 20);
		RagdollSettings.setDebugLogging(false); // its default floods the server log
	}
}
