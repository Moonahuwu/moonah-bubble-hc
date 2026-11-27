package moonahmc.bubble;

import dev.leo.ragdollcorpse.corpse.CorpseDeathHandler;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Ragdoll Corpse, if it's installed (only called once {@link Ragdolls} has checked). */
final class CorpseCompat {
	private CorpseCompat() {
	}

	/** The corpse Ragdoll Corpse just made for `p`, or null. */
	static UUID corpse(ServerPlayer p) {
		return CorpseDeathHandler.peekPendingCorpse(p.getUUID());
	}

	static void forget(ServerPlayer p) {
		CorpseDeathHandler.takePendingCorpse(p.getUUID());
	}
}
