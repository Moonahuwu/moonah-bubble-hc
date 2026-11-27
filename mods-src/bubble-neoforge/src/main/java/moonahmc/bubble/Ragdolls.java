package moonahmc.bubble;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bubble with Sable Ragdolls (and Ragdoll Corpse), when they're installed; without them every call does nothing.
 * <ul>
 * <li>Going down (revive) turns you into a ragdoll that stays down until someone gets you up. Teammates can grab and
 * drag you meanwhile.</li>
 * <li>A death that ends the run: the corpse keeps your gear (Bubble keeps your inventory for "Another life", which
 * used to leave it bare), and the one in the kill cam drops at the moment of death in the replay rather than lying
 * there all through it.</li>
 * </ul>
 * Off with ragdollDowns in config/bubble.json.
 */
final class Ragdolls {
	private static Boolean ragdolls;
	private static Boolean corpses;
	private static boolean broken;
	/** What run-ending deaths were wearing (a RagdollEquipmentSnapshot), taken before Bubble keeps the inventory. */
	private static final Map<UUID, Object> outfits = new HashMap<>();

	/**
	 * The body Ragdoll Corpse just made for each player who died, noted straight after it makes it: by the time the
	 * player has finished dying, its own item-drop step has already taken it off its list, so asking then finds nothing
	 * (that's why the kill cam showed two bodies, and why run-ending corpses came out bare).
	 */
	private static final Map<UUID, UUID> corpseIds = new HashMap<>();

	/** The last hit each player took (amount before armour), for how hard it should throw them. */
	private static final Map<UUID, Float> lastHit = new HashMap<>();
	/** How the hit that killed (or downed) each player should throw their body (m/s): {death, down}. */
	private static final Map<UUID, Vec3> deathFling = new HashMap<>();
	private static final Map<UUID, Vec3> downFling = new HashMap<>();

	/** From LivingIncomingDamageEvent: remember how big the hit was. */
	static void noteHit(ServerPlayer p, float amount) {
		lastHit.put(p.getUUID(), amount);
	}

	/**
	 * How a hit should throw a body: explosions blow you away from the blast (harder the more it hurt), and big hits
	 * (5 hearts or more) knock you away from whatever did it. Zero for anything else.
	 */
	static Vec3 fling(ServerPlayer p, DamageSource source) {
		Vec3 from = source.getSourcePosition();
		if (from == null) {
			return Vec3.ZERO;
		}
		float amount = lastHit.getOrDefault(p.getUUID(), 0f);
		boolean blast = source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION);
		double speed = blast ? net.minecraft.util.Mth.clamp(8 + amount * 0.8, 10, 35)
				: amount >= 10 ? net.minecraft.util.Mth.clamp(amount * 0.5, 5, 18) : 0;
		if (speed <= 0) {
			return Vec3.ZERO;
		}
		Vec3 d = p.position().add(0, 1, 0).subtract(from);
		d = d.lengthSqr() < 1e-4 ? new Vec3(0, 1, 0) : d.normalize();
		// always some lift, so it flies rather than slides
		return new Vec3(d.x * speed, Math.max(d.y * speed, speed * 0.45), d.z * speed);
	}

	/** For the kill cam's body: how the death (or the down, if they bled out) should throw it. */
	static Vec3 flingFor(UUID id, boolean fromDown) {
		return (fromDown ? downFling : deathFling).getOrDefault(id, Vec3.ZERO);
	}

	/** From LivingDeathEvent (LOW): how this death should throw the body. */
	static void noteDeath(ServerPlayer p, DamageSource source) {
		deathFling.put(p.getUUID(), fling(p, source));
	}

	/** From LivingDeathEvent (LOW, so after Ragdoll Corpse's HIGH): remember the body it just made. */
	static void noteCorpse(ServerPlayer p) {
		if (!corpses()) {
			return;
		}
		try {
			UUID id = CorpseCompat.corpse(p);
			if (id != null) {
				corpseIds.put(p.getUUID(), id);
			}
		} catch (Throwable t) {
			failed(t);
		}
	}

	/** Bodies to take away that weren't there yet when asked (still being put together): id -> {level, ticks left}. */
	private static final Map<UUID, Object[]> toRemove = new HashMap<>();
	private static boolean ticking;

	private Ragdolls() {
	}

	/**
	 * Takes a body away now, or as soon as it exists: a new ragdoll is queued and only put together a tick or two later,
	 * and removing it before then does nothing (which is how the kill cam ended up with two bodies).
	 */
	static void removeSoon(ServerLevel level, UUID id) {
		boolean gone = false;
		try {
			gone = RagdollCompat.remove(level, id);
		} catch (Throwable t) {
			failed(t);
			return;
		}
		if (gone) {
			return;
		}
		toRemove.put(id, new Object[] {level, 100});
		if (!ticking) {
			ticking = true;
			net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) -> retryRemovals());
		}
	}

	private static void retryRemovals() {
		if (toRemove.isEmpty() || broken) {
			return;
		}
		for (var it = toRemove.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			ServerLevel level = (ServerLevel) e.getValue()[0];
			int left = (Integer) e.getValue()[1] - 1;
			boolean gone;
			try {
				gone = RagdollCompat.remove(level, e.getKey());
			} catch (Throwable t) {
				failed(t);
				return;
			}
			if (gone) {
				Bubble.LOG.info("Ragdolls: took the body away once it was there ({} ticks later)", 100 - left);
				it.remove();
			} else if (left <= 0) {
				Bubble.LOG.warn("Ragdolls: couldn't take a body away ({}), it never turned up", e.getKey());
				it.remove();
			} else {
				e.getValue()[1] = left;
			}
		}
	}

	static boolean available() {
		if (ragdolls == null) {
			ragdolls = ModList.get().isLoaded("sable_player_ragdoll");
			corpses = ModList.get().isLoaded("ragdoll_corpse");
		}
		return ragdolls && !broken;
	}

	private static boolean corpses() {
		return available() && corpses;
	}

	private static void failed(Throwable t) {
		if (!broken) {
			broken = true;
			Bubble.LOG.error("Ragdolls: a call into Sable Ragdolls failed, so Bubble stops using it until a restart", t);
		}
	}

	/** Revive: `p` just went down from `source`. */
	static void down(ServerPlayer p, DamageSource source) {
		if (!available() || !BubbleServer.config.ragdollDowns) {
			return;
		}
		// thrown a little away from whatever did it, on top of how they were moving (m/s)
		Vec3 push = Vec3.ZERO;
		Vec3 from = source.getSourcePosition();
		if (from != null) {
			Vec3 d = p.position().subtract(from);
			d = new Vec3(d.x, 0, d.z);
			if (d.lengthSqr() > 1e-4) {
				push = d.normalize().scale(4);
			}
		}
		Vec3 vel = p.getDeltaMovement().scale(20).add(push).add(0, 3, 0);
		if (vel.length() > 20) {
			vel = vel.normalize().scale(20);
		}
		// an explosion or a big hit throws you properly
		Vec3 thrown = fling(p, source);
		downFling.put(p.getUUID(), thrown);
		if (thrown.length() > vel.length()) {
			vel = thrown;
		}
		try {
			RagdollCompat.down(p, vel);
		} catch (Throwable t) {
			failed(t);
		}
	}

	/** The wheel: `p` goes limp and gets thrown about for `ticks`, then gets up (not if they're down already). */
	static void toss(ServerPlayer p, int ticks) {
		if (!available() || Revive.isDown(p.getUUID()) || p.isSpectator()) {
			return;
		}
		var r = p.getRandom();
		double a = r.nextDouble() * Math.PI * 2;
		Vec3 vel = new Vec3(Math.cos(a) * 3, 5 + r.nextDouble() * 2, Math.sin(a) * 3);
		try {
			RagdollCompat.toss(p, vel, Math.max(20, ticks));
		} catch (Throwable t) {
			failed(t);
		}
	}

	/** The wheel's Yeet: launched up and away at `speed` m/s as a ragdoll, back up 6 seconds later. */
	static void yeet(ServerPlayer p, double speed) {
		if (!available() || Revive.isDown(p.getUUID()) || p.isSpectator()) {
			return;
		}
		double a = p.getRandom().nextDouble() * Math.PI * 2;
		double s = Math.max(5, Math.min(80, speed));
		Vec3 vel = new Vec3(Math.cos(a) * s * 0.45, s, Math.sin(a) * s * 0.45);
		try {
			RagdollCompat.toss(p, vel, 120);
		} catch (Throwable t) {
			failed(t);
		}
	}

	/** Revive: `p` got up (revived, left, or the run ended). */
	static void up(ServerPlayer p) {
		if (!available()) {
			return;
		}
		try {
			RagdollCompat.up(p);
		} catch (Throwable t) {
			failed(t);
		}
	}

	static boolean isRagdolled(ServerPlayer p) {
		if (!available()) {
			return false;
		}
		try {
			return RagdollCompat.isRagdolled(p);
		} catch (Throwable t) {
			failed(t);
			return false;
		}
	}

	/** Just before a run-ending death empties `p`'s inventory: remember their gear for the corpse. */
	static void rememberOutfit(ServerPlayer p) {
		if (!available()) {
			return;
		}
		try {
			outfits.put(p.getUUID(), RagdollCompat.outfit(p));
		} catch (Throwable t) {
			failed(t);
		}
	}

	/** Once `p` has died: their corpse gets the gear they had on. */
	static void dressCorpse(ServerPlayer p) {
		Object outfit = outfits.get(p.getUUID());
		if (outfit == null || !corpses()) {
			return;
		}
		try {
			UUID corpse = corpseIds.get(p.getUUID());
			if (corpse != null) {
				RagdollCompat.dress(p.serverLevel(), corpse, outfit);
			}
		} catch (Throwable t) {
			failed(t);
		}
	}

	/**
	 * The kill cam is about to replay `p`'s death: their corpse goes (so it isn't lying there before they've died in
	 * the replay) and comes back at the moment of death ({@link #dropBody}). Returns their outfit for that.
	 */
	static Object takeCorpse(ServerPlayer p) {
		Object outfit = outfits.remove(p.getUUID());
		if (!corpses()) {
			return outfit;
		}
		try {
			UUID corpse = corpseIds.remove(p.getUUID());
			if (corpse != null) {
				removeSoon(p.serverLevel(), corpse);
				CorpseCompat.forget(p);
			} else {
				Bubble.LOG.info("Ragdolls: no Ragdoll Corpse body found for {} to hold back for the kill cam", p.getGameProfile().getName());
			}
		} catch (Throwable t) {
			failed(t);
		}
		return outfit;
	}

	/** The kill cam reached the moment of death: the body drops there. */
	static boolean dropBody(ServerLevel level, Vec3 pos, float yaw, GameProfile profile, Vec3 velocity, Object outfit) {
		if (!available()) {
			return false;
		}
		try {
			return RagdollCompat.body(level, pos, yaw, profile, velocity, outfit);
		} catch (Throwable t) {
			failed(t);
			return false;
		}
	}

	static void clear() {
		outfits.clear();
		corpseIds.clear();
		deathFling.clear();
		downFling.clear();
	}

	/** /bubble ragdolltest: drops a body where the command runs (checks Bubble can talk to Sable Ragdolls). */
	static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> command() {
		return net.minecraft.commands.Commands.literal("ragdolltest").requires(s -> s.hasPermission(2))
				.then(net.minecraft.commands.Commands.literal("remove").executes(c -> {
					// a body made the way Ragdoll Corpse makes one, taken away straight after (what the kill cam does)
					var src = c.getSource();
					if (!available()) {
						src.sendFailure(net.minecraft.network.chat.Component.literal("Sable Ragdolls isn't installed"));
						return 0;
					}
					UUID id = RagdollCompat.corpseLike(src.getLevel(), src.getPosition(), new GameProfile(UUID.randomUUID(), "RagdollTest"));
					boolean now = id != null && RagdollCompat.remove(src.getLevel(), id);
					if (id != null && !now) {
						removeSoon(src.getLevel(), id);
					}
					src.sendSuccess(() -> net.minecraft.network.chat.Component.literal("ragdolltest remove: " + (id == null ? "no body made"
							: now ? "gone straight away" : "not there yet, retrying (see the log)")), false);
					return 1;
				}))
				.executes(c -> {
			var src = c.getSource();
			if (!available()) {
				src.sendFailure(net.minecraft.network.chat.Component.literal("Sable Ragdolls isn't installed (or failed earlier)"));
				return 0;
			}
			dropBody(src.getLevel(), src.getPosition(), 0, new GameProfile(UUID.randomUUID(), "RagdollTest"), new Vec3(2, 3, 0), null);
			boolean ok = !broken;
			src.sendSuccess(() -> net.minecraft.network.chat.Component.literal("ragdolltest: " + (ok ? "body dropped" : "failed, see the log")), false);
			return ok ? 1 : 0;
		});
	}
}
