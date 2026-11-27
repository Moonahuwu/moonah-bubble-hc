package moonahmc.bubble;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings stored in config/bubble.json on the server. */
public final class BubbleConfig {
	public static final int MIN_RADIUS = 2;
	public static final int MAX_RADIUS = 500;
	public static final int DEFAULT_RADIUS = 50;
	public static final int MIN_REVIVE_SECONDS = 5;
	public static final int MAX_REVIVE_SECONDS = 300;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Keep everyone inside the bubble. */
	public boolean bubbleEnabled = true;
	/** false = teleport leavers to the nearest player, true = push them back towards the group. */
	public boolean pushMode = false;
	/** Bubble wrap: leave by one side, come back in on the opposite side (like screen wrap). Wins over pushMode. */
	public boolean wrapMode = false;
	/** Everyone on one scoreboard team (friendly fire on), so allied-checking weapons and abilities skip teammates. */
	public boolean teamUp = true;
	/** Everyone shares one health bar. */
	public boolean soulLink = false;
	/** Any death ends the run: kill cam, post-game stats, then a vote on what's next. */
	public boolean hardcoreReset = true;
	/** true = the bubble is a sphere (it also limits how far apart people can be up and down). */
	public boolean vertical = false;
	public int radius = DEFAULT_RADIUS;
	/** Which run (world) this is; goes up by one on every reset. */
	public int run = 1;
	/** Extra lives used in this run ("Another life" votes). */
	public int livesUsed = 0;
	/** Going down instead of dying: a teammate standing next to you gets you back up (see Revive). */
	public boolean reviveEnabled = true;
	/** Seconds you have while you're down before you bleed out. */
	public int reviveSeconds = 30;
	/** Seconds a teammate has to stand next to you to get you up (0 = straight away). */
	public int reviveHoldSeconds = 3;
	/** After you're revived, seconds before you can go down again (0 = no wait). Go down in that time and you just die. */
	public int reviveCooldownSeconds = 300;
	/** The shop only opens for a while at key moments of a run (ShopWindows). Seconds for each (0 = that one doesn't open it). */
	public boolean shopWindows = true;
	public int shopStartSeconds = 120;
	public int shopDimensionSeconds = 90;
	public int shopBossSeconds = 120;
	public int shopEliteSeconds = 60;
	public int shopChestSeconds = 60;
	/** Lootr chests a player opens per shop window. */
	public int shopChests = 10;
	/** Shop tabs that stay open between shop windows (by tab name). Charging is the Charging tab. */
	public java.util.List<String> shopAlwaysOpen = new java.util.ArrayList<>(java.util.List.of("Essentials", "Video", "Charging", "Guns"));
	/** How fragile everyone is (Ragdoll Reactions / Sable Ragdolls), set from Admin > Ragdolls. */
	public RagdollTuning.Tune ragdolls = new RagdollTuning.Tune();
	/** With Sable Ragdolls installed: going down turns you into a ragdoll until you're revived. */
	public boolean ragdollDowns = true;
	/** A fall from at least this many blocks kills you outright, no going down (0 = falls always let you go down). */
	public int noReviveFallBlocks = 30;
	/** How much of your armour bullets ignore (0 = none, 1 = all). Gun damage is TACZ's DamageBaseMultiplier (tacz-server.toml). */
	public double gunArmorPen = 0.8;
	/** Seed for the next new world (set with /bubble seed). Empty = random. */
	public String nextSeed = "";

	public static BubbleConfig load(Path path) {
		BubbleConfig config = new BubbleConfig();
		if (Files.exists(path)) {
			try {
				BubbleConfig read = GSON.fromJson(Files.readString(path), BubbleConfig.class);
				if (read != null) {
					config = read;
				}
			} catch (Exception e) {
				Bubble.LOG.warn("config/bubble.json could not be read, using defaults: {}", e.toString());
			}
		}
		config.clamp();
		config.save(path);
		return config;
	}

	public void clamp() {
		radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
		run = Math.max(1, run);
		livesUsed = Math.max(0, livesUsed);
		if (reviveSeconds <= 0) {
			reviveSeconds = 30; // a config from before revive existed
		}
		reviveSeconds = Math.max(MIN_REVIVE_SECONDS, Math.min(MAX_REVIVE_SECONDS, reviveSeconds));
		reviveHoldSeconds = Math.max(0, Math.min(30, reviveHoldSeconds));
		reviveCooldownSeconds = Math.max(0, Math.min(3600, reviveCooldownSeconds));
		noReviveFallBlocks = Math.max(0, Math.min(400, noReviveFallBlocks));
		shopStartSeconds = Math.max(0, Math.min(3600, shopStartSeconds));
		shopDimensionSeconds = Math.max(0, Math.min(3600, shopDimensionSeconds));
		shopBossSeconds = Math.max(0, Math.min(3600, shopBossSeconds));
		shopEliteSeconds = Math.max(0, Math.min(3600, shopEliteSeconds));
		shopChestSeconds = Math.max(0, Math.min(3600, shopChestSeconds));
		shopChests = Math.max(1, Math.min(500, shopChests));
		if (ragdolls == null) {
			ragdolls = new RagdollTuning.Tune();
		}
		ragdolls.clamp();
		if (shopAlwaysOpen == null) {
			shopAlwaysOpen = new java.util.ArrayList<>();
		}
		gunArmorPen = Double.isNaN(gunArmorPen) ? 0.8 : Math.max(0, Math.min(1, gunArmorPen));
		if (nextSeed == null) {
			nextSeed = "";
		}
	}

	public void save(Path path) {
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(this));
		} catch (IOException e) {
			Bubble.LOG.error("Could not save config/bubble.json", e);
		}
	}
}
