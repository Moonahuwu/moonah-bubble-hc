package moonahmc.bubble;

import dev.shadowsoffire.apotheosis.mobs.registries.EliteRegistry;
import dev.shadowsoffire.apotheosis.mobs.types.Elite;
import dev.shadowsoffire.apotheosis.tiers.GenContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;

/**
 * Apotheosis elites of Bubble's mobs (Gatling Ace, Swarm Queen, Ancient Watcher...). The elites themselves are data
 * (data/bubble/apothic_elites); Apotheosis rolls them when the mobs spawn near a player. This is for /bubble elite and
 * for telling elites apart. Only loaded when Apotheosis is installed.
 */
final class ApothElites {
	private ApothElites() {
	}

	/** Set by Apotheosis on a mob once it's been made an elite (or a boss). */
	static boolean isElite(Entity e) {
		return e.getPersistentData().contains("apoth.miniboss") || e.getPersistentData().contains("apoth.boss");
	}

	/** Turns `mob` (already in the world) into one of its elites, for the nearest player's world tier. */
	static boolean makeElite(ServerLevel level, Mob mob) {
		Entity near = level.getNearestPlayer(mob, 128);
		ServerPlayer p = near instanceof ServerPlayer sp ? sp : net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
		GenContext ctx = GenContext.forPlayerAtPos(level.random, p, mob.blockPosition());
		Elite elite = EliteRegistry.INSTANCE.getRandomItem(ctx, mob);
		if (elite == null) {
			return false;
		}
		elite.transformMiniboss(level, mob, ctx);
		return true;
	}

	/**
	 * Spawns `type` at pos as one of its elites (for the player's world tier). Returns the mob, or null if that mob
	 * has no elite or couldn't spawn.
	 */
	static Mob spawnElite(ServerLevel level, EntityType<?> type, BlockPos pos, ServerPlayer forPlayer) {
		ServerPlayer p = forPlayer != null ? forPlayer : net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
		Entity probe = type.create(level);
		if (!(probe instanceof Mob)) {
			return null;
		}
		GenContext ctx = GenContext.forPlayerAtPos(level.random, p, pos);
		Elite elite = EliteRegistry.INSTANCE.getRandomItem(ctx, probe);
		probe.discard();
		if (elite == null) {
			return null;
		}
		Entity e = type.spawn(level, pos, MobSpawnType.COMMAND);
		if (!(e instanceof Mob mob)) {
			return null;
		}
		if (!isElite(mob)) {
			elite.transformMiniboss(level, mob, ctx);
		}
		return mob;
	}
}
