package moonahmc.bubble;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

import java.util.List;

/**
 * The director: decides when random events happen, by how the run is going rather than on a timer (Left 4 Dead style).
 * <ul>
 * <li><b>Heat</b> goes up when players take damage, go down, have hostile mobs after them, or are fighting a boss, and
 * fades over about half a minute.</li>
 * <li>While it's low, <b>calm</b> builds up. When the group has been calm for a while (calmMin to calmMax minutes in
 * config/bubble-events.json, 3 to 6 by default), something happens: usually a threat, sometimes a gift (supply drop,
 * free spin, a short shop window). Travelling and pearl hunting stop being dead time.</li>
 * <li>While things are hot, nothing random is added on top.</li>
 * <li>When a real fight ends, there's a good chance of a gift once things have settled (a breather and a reward).</li>
 * </ul>
 * Off ("director": false, or /bubble director off) goes back to the plain timer (minMinutes to maxMinutes).
 */
final class Director {
	/** Below this the group is calm; at or above INTENSE it's a fight. */
	private static final double CALM = 10;
	private static final double INTENSE = 35;
	/** At least this long between two director events (seconds). */
	private static final int MIN_GAP = 90;

	private static double heat;
	private static int calm;
	private static int target = -1;
	private static int intense;
	private static int sinceLast = MIN_GAP;
	/** Seconds until a post-fight gift (-1 = none coming). */
	private static int giftIn = -1;

	private Director() {
	}

	static void init() {
		NeoForge.EVENT_BUS.addListener((LivingDamageEvent.Post e) -> {
			if (e.getEntity() instanceof ServerPlayer p && BubbleServer.counts(p) && e.getNewDamage() > 0) {
				heat += Math.min(20, e.getNewDamage() * 2);
			}
		});
	}

	/** Revive: someone went down. */
	static void down() {
		heat += 25;
	}

	static void reset() {
		heat = 0;
		calm = 0;
		target = -1;
		intense = 0;
		sinceLast = MIN_GAP;
		giftIn = -1;
	}

	/** Once a second while a run is on and someone is playing (from RandomEvents). */
	static void tick(MinecraftServer s, int calmMin, int calmMax) {
		List<ServerPlayer> playing = s.getPlayerList().getPlayers().stream().filter(BubbleServer::counts).toList();
		// mobs after someone keep the heat up
		int hunters = 0;
		for (ServerPlayer p : playing) {
			hunters += p.serverLevel().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(32),
					m -> m instanceof Enemy && m.getTarget() == p && m.isAlive()).size();
		}
		heat += Math.min(10, hunters * 1.5);
		if (Bosses.fighting()) {
			heat = Math.max(heat, 70);
		}
		heat *= 0.96;
		sinceLast++;
		if (target < 0) {
			int lo = Math.max(1, Math.min(calmMin, calmMax)) * 60;
			int hi = Math.max(lo, calmMax * 60);
			target = lo + s.overworld().random.nextInt(hi - lo + 1);
		}
		if (heat >= INTENSE) {
			intense++;
			calm = Math.max(0, calm - 5);
			giftIn = -1;
		} else if (heat < CALM) {
			calm++;
			if (intense >= 30 && giftIn < 0 && s.overworld().random.nextFloat() < 0.6f) {
				giftIn = 15; // the fight's over: something nice in a moment
			}
			intense = 0;
		}
		if (Wheel.active()) {
			return;
		}
		if (giftIn >= 0 && --giftIn <= 0) {
			giftIn = -1;
			if (heat < CALM && RandomEvents.fire(true)) {
				sinceLast = 0;
				calm = Math.max(0, calm - 60);
			}
			return;
		}
		if (calm >= target && sinceLast >= MIN_GAP && heat < CALM) {
			boolean gift = s.overworld().random.nextFloat() < 0.3f;
			if (RandomEvents.fire(gift) || (gift && RandomEvents.fire(false))) {
				sinceLast = 0;
			}
			calm = 0;
			target = -1;
		}
	}

	private static String clock(int secs) {
		return secs / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", secs % 60);
	}

	/** For the events editor and /bubble director. */
	static String status() {
		String state = heat >= INTENSE ? "a fight is on" : heat < CALM ? "calm" : "warming up";
		return "Director: " + state + " (heat " + Math.round(heat) + "), calm " + clock(calm)
				+ (target > 0 ? " of " + clock(target) : "") + (giftIn > 0 ? ", a gift is coming" : "");
	}

	/** /bubble director [on|off] */
	static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> command() {
		return Commands.literal("director").requires(s -> s.hasPermission(2))
				.executes(c -> {
					c.getSource().sendSuccess(() -> Component.literal((RandomEvents.director() ? "" : "(off: events use the timer) ") + status()), false);
					return 1;
				})
				.then(Commands.literal("on").executes(c -> set(c.getSource(), true)))
				.then(Commands.literal("off").executes(c -> set(c.getSource(), false)));
	}

	private static int set(CommandSourceStack src, boolean on) {
		RandomEvents.edit("director", 0, on ? 1 : 0);
		reset();
		src.sendSuccess(() -> Component.literal(on ? "Random events now come when things are quiet (the director)."
				: "Random events are back on the timer.").withStyle(ChatFormatting.AQUA), true);
		return 1;
	}
}
