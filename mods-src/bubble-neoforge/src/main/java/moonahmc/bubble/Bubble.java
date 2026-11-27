package moonahmc.bubble;

import moonahmc.bubble.net.Networking;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bubble: everyone stays inside a shared circle around the group; one death can reset the world. */
@Mod(Bubble.MOD_ID)
public final class Bubble {
	public static final String MOD_ID = "bubble";
	public static final Logger LOG = LoggerFactory.getLogger("Bubble");

	public Bubble(IEventBus modBus, Dist dist) {
		Payloads.register();
		ModContent.register(modBus); // rainbow + nuke creepers
		modBus.addListener(Networking::register);
		// On a dedicated server, a pending reset archives the old world before Minecraft loads it.
		if (dist == Dist.DEDICATED_SERVER) {
			WorldArchiver.archiveIfRequested();
		}
		BubbleServer.init();
		Pregame.init(); // "Next run" settings, creeper spawning
		Wheel.init(); // start-of-run wheel
		WheelStatus.init(); // the mid-run wheel's timed statuses
		RandomEvents.init(); // hordes, watchers, supply drops every so often
		WheelAdmin.init(); // Admin tab: wheel slices and events
		RunLog.init(); // the post-game "What happened" list
		Revive.init(); // going down instead of dying, teammates revive you
		CurrencyAdmin.init(); // ops: give/take/set Strange Matter and Eidolons
		Gamble.init(); // case opening (shop Gamble tab)
		Lobby.init(); // ready-up lobby at the start of a run
		RunModifiers.init(); // wheel modifiers + upgrade attributes
		Eidolons.init(); // permanent currency, upgrades, cosmetics, hall of fame, bets
		Goals.init(); // game modes, stages and the goals HUD
		Guns.init(); // TACZ guns on rare mobs
		MobDrops.init(); // cases, spins and Strange Matter from mobs
		RagdollTuning.init(); // how fragile ragdolls are, live from Admin > Ragdolls
		Director.init(); // random events come in the lulls
		ShopWindows.init(); // the shop only opens at key moments of a run
		RunTally.init(); // downs and deaths this run in the tab list; everyone on one team
		Bosses.init(); // bosses pay everyone who helped, boss cam, mini-boss elites, the wheel's "Summon a boss"
		VideoShare.init(); // shop Video tab
		AccessoryCompat.init(); // Curios: right-click to equip accessories
		RelicMax.init(); // artifacts / relics come fully levelled
		TransmutationOff.init(); // ProjectE: no Transmutation Table / Condensers, no money-making Philosopher's Stone swaps
		// Shared Apotheosis world tiers (only when Apotheosis is installed)
		if (net.neoforged.fml.ModList.get().isLoaded("apotheosis")) {
			ApothTiers.init();
			EliteNames.init(); // elites' names pulse above their heads (client Rarity)
		}
	}
}
