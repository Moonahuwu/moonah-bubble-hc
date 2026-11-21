package moonahmc.bubble;


import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hardcore reset, part 2. When a run ends, the mod writes restart-request.json and stops the server.
 * The MoonahMC panel sees that file and starts the server again; on that start (before the world loads)
 * this moves the old world into world-archives/ and clears the seed, so Minecraft creates a fresh world.
 */
public final class WorldArchiver {
	public static final String REQUEST_FILE = "restart-request.json";
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");

	private WorldArchiver() {
	}

	/** seed = the seed for the next world ("" = random; this world's seed again = a retry). */
	static void request(int finishedRun, String seed) {
		Path file = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve(REQUEST_FILE);
		String json = "{\n  \"reason\": \"bubble-reset\",\n  \"run\": " + finishedRun + ",\n  \"seed\": \""
				+ seed.replaceAll("[^0-9A-Za-z_-]", "") + "\",\n  \"time\": \"" + LocalDateTime.now().format(STAMP) + "\"\n}\n";
		try {
			Files.writeString(file, json);
		} catch (IOException e) {
			Bubble.LOG.error("Could not write {}", file, e);
		}
	}

	public static void archiveIfRequested() {
		Path game = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
		Path request = game.resolve(REQUEST_FILE);
		if (!Files.exists(request)) {
			return;
		}
		try {
			String text = Files.readString(request);
			if (!text.contains("bubble-reset")) {
				return;
			}
			Matcher m = Pattern.compile("\"run\"\\s*:\\s*(\\d+)").matcher(text);
			String run = m.find() ? m.group(1) : "?";

			Path propsFile = game.resolve("server.properties");
			Properties props = new Properties();
			if (Files.exists(propsFile)) {
				try (Reader r = Files.newBufferedReader(propsFile, StandardCharsets.UTF_8)) {
					props.load(r);
				}
			}
			String level = props.getProperty("level-name", "world").trim();
			Path world = game.resolve(level);
			if (Files.isDirectory(world)) {
				Path archives = game.resolve("world-archives");
				Files.createDirectories(archives);
				Path dest = archives.resolve("run-" + run + "_" + LocalDateTime.now().format(STAMP));
				for (int i = 2; Files.exists(dest); i++) {
					dest = archives.resolve("run-" + run + "_" + LocalDateTime.now().format(STAMP) + "_" + i);
				}
				Files.move(world, dest);
				Bubble.LOG.info("Run {} ended: archived the old world to {}", run, game.relativize(dest));
				// Keep the world's datapacks (custom loot, recipes, tweaks...) for the next run.
				Path oldPacks = dest.resolve("datapacks");
				if (Files.isDirectory(oldPacks)) {
					copyTree(oldPacks, world.resolve("datapacks"));
					Bubble.LOG.info("Carried the world's datapacks over to the new run");
				}
			}
			// Pre-game settings (lucky blocks, structures) for the new world
			Pregame.applyStaged(game, world.resolve("datapacks"));
			Matcher seed = Pattern.compile("\"seed\"\\s*:\\s*\"([^\"]*)\"").matcher(text);
			String next = seed.find() ? seed.group(1) : "";
			setProperty(propsFile, "level-seed", next); // empty seed = a new random world
			if (!next.isEmpty()) {
				Bubble.LOG.info("The next world uses seed {}", next);
			}
			Files.delete(request);
		} catch (IOException e) {
			Bubble.LOG.error("Could not archive the old world; it will be loaded again", e);
		}
	}

	private static void copyTree(Path from, Path to) throws IOException {
		try (var walk = Files.walk(from)) {
			for (Path p : (Iterable<Path>) walk::iterator) {
				Path target = to.resolve(from.relativize(p).toString());
				if (Files.isDirectory(p)) {
					Files.createDirectories(target);
				} else {
					Files.createDirectories(target.getParent());
					Files.copy(p, target);
				}
			}
		}
	}

	private static void setProperty(Path file, String key, String value) throws IOException {
		List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>();
		boolean found = false;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).startsWith(key + "=")) {
				lines.set(i, key + "=" + value);
				found = true;
			}
		}
		if (!found) {
			lines.add(key + "=" + value);
		}
		Files.write(file, lines);
	}
}
