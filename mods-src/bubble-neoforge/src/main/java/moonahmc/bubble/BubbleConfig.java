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
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Keep everyone inside the bubble. */
	public boolean bubbleEnabled = true;
	/** false = teleport leavers to the nearest player, true = push them back towards the group. */
	public boolean pushMode = false;
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
