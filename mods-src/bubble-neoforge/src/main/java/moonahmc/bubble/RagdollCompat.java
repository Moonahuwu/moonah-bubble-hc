package moonahmc.bubble;

import com.mojang.authlib.GameProfile;
import dev.leo.sableplayerragdoll.api.DespawnCondition;
import dev.leo.sableplayerragdoll.api.PlayerlessDespawnRule;
import dev.leo.sableplayerragdoll.api.PlayerlessRagdollSession;
import dev.leo.sableplayerragdoll.api.RagdollAPI;
import dev.leo.sableplayerragdoll.api.RagdollEquipmentScope;
import dev.leo.sableplayerragdoll.api.RagdollEquipmentSnapshot;
import dev.leo.sableplayerragdoll.api.RagdollLaunchOptions;
import dev.leo.sableplayerragdoll.api.RagdollSession;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * The calls into Sable Ragdolls and Ragdoll Corpse. Only loaded through {@link Ragdolls}, after it has checked the
 * mods are there.
 */
final class RagdollCompat {
	private RagdollCompat() {
	}

	/** Knocked down: limp, and stays that way (can't get up by themselves) until revived. */
	static void down(ServerPlayer p, Vec3 velocity) {
		if (RagdollAPI.isRagdolled(p)) {
			RagdollSession s = RagdollAPI.activeSession(p);
			if (s != null) {
				s.setDismountLocked(true);
			}
			return;
		}
		RagdollAPI.launch(p, velocity, RagdollLaunchOptions.builder()
				.autoSeat(true)
				.lockDismount(true)
				.despawnConditions(List.of(DespawnCondition.never()))
				.build());
	}

	/** Limp for `ticks`, then back up by themselves. */
	static void toss(ServerPlayer p, Vec3 velocity, int ticks) {
		if (RagdollAPI.isRagdolled(p)) {
			return;
		}
		RagdollAPI.launch(p, velocity, RagdollLaunchOptions.builder()
				.autoSeat(true)
				.lockDismount(true)
				.despawnConditions(List.of(DespawnCondition.afterTicks(ticks)))
				.build());
	}

	/** Revived (or the run ended): back on their feet. */
	static void up(ServerPlayer p) {
		RagdollSession s = RagdollAPI.activeSession(p);
		if (s != null) {
			s.setDismountLocked(false);
			s.release();
		}
	}

	static boolean isRagdolled(ServerPlayer p) {
		return RagdollAPI.isRagdolled(p);
	}

	static Object outfit(ServerPlayer p) {
		return RagdollAPI.captureEquipment(p, RagdollEquipmentScope.ALL);
	}

	static void dress(ServerLevel level, UUID corpse, Object outfit) {
		RagdollAPI.applyEquipmentSnapshot(level, corpse, (RagdollEquipmentSnapshot) outfit);
	}

	/** True if it was there and is gone now (a body that's still being put together isn't there yet). */
	static boolean remove(ServerLevel level, UUID corpse) {
		return RagdollAPI.remove(level, corpse);
	}

	/** A body like Ragdoll Corpse makes (for /bubble ragdolltest remove): its id, or null. */
	static UUID corpseLike(ServerLevel level, Vec3 pos, GameProfile profile) {
		PlayerlessRagdollSession s = RagdollAPI.spawnPlayerless(level, pos, 0, profile, Vec3.ZERO, PlayerlessDespawnRule.never());
		return s == null ? null : s.id();
	}

	/** A body (no player in it) that stays where it lands. */
	static boolean body(ServerLevel level, Vec3 pos, float yaw, GameProfile profile, Vec3 velocity, Object outfit) {
		PlayerlessRagdollSession s = RagdollAPI.spawnPlayerless(level, pos, yaw, profile, velocity, PlayerlessDespawnRule.never());
		if (s == null) {
			return false;
		}
		RagdollAPI.setCorpse(level, s.id(), true);
		UUID torso = RagdollAPI.torsoSubLevelId(s.id());
		if (torso != null) {
			RagdollAPI.setGrabDisabled(level, torso, true);
		}
		if (outfit != null) {
			RagdollAPI.applyEquipmentSnapshot(level, s.id(), (RagdollEquipmentSnapshot) outfit);
		}
		return true;
	}
}
