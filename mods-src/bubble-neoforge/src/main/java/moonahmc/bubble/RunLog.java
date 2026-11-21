package moonahmc.bubble;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What happened this run, for the post-game screen's "What happened" list: what the start wheel landed on, every
 * mid-run spin, slices an op did, and random events. Kept in the world folder (bubble-runlog.json), so a new world
 * starts a new list and a server restart mid-run keeps it.
 */
public final class RunLog {
	public static final String WHEEL = "wheel";
	public static final String SPIN = "spin";
	public static final String EVENT = "event";
	public static final String ADMIN = "admin";
	private static final int MAX = 300;

	/** t = overworld game time (the same clock as the run's length). */
	record Entry(long t, String kind, String text) {
	}

	private static final List<Entry> entries = new ArrayList<>();
	private static MinecraftServer server;
	private static Path file;

	private RunLog() {
	}

	static void init() {
		var bus = NeoForge.EVENT_BUS;
		bus.addListener((ServerStartedEvent e) -> {
			server = e.getServer();
			file = server.getWorldPath(LevelResource.ROOT).resolve("bubble-runlog.json");
			load();
		});
		bus.addListener((ServerStoppedEvent e) -> {
			server = null;
			entries.clear();
		});
	}

	static void add(String kind, String text) {
		if (server == null) {
			return;
		}
		entries.add(new Entry(server.overworld().getGameTime(), kind, text));
		while (entries.size() > MAX) {
			entries.remove(0);
		}
		save();
	}

	/** For the post-game packet: "ticks\tkind\ttext", oldest first. */
	static List<String> lines() {
		List<String> out = new ArrayList<>();
		for (Entry e : entries) {
			out.add(e.t() + "\t" + e.kind() + "\t" + e.text());
		}
		return out;
	}

	private static void load() {
		entries.clear();
		try {
			if (file != null && Files.exists(file)) {
				JsonArray arr = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("entries");
				arr.forEach(x -> {
					JsonObject o = x.getAsJsonObject();
					entries.add(new Entry(o.get("t").getAsLong(), o.get("kind").getAsString(), o.get("text").getAsString()));
				});
			}
		} catch (Exception e) {
			Bubble.LOG.warn("Could not read {}", file, e);
		}
	}

	private static void save() {
		if (file == null) {
			return;
		}
		try {
			JsonArray arr = new JsonArray();
			for (Entry e : entries) {
				JsonObject o = new JsonObject();
				o.addProperty("t", e.t());
				o.addProperty("kind", e.kind());
				o.addProperty("text", e.text());
				arr.add(o);
			}
			JsonObject root = new JsonObject();
			root.add("entries", arr);
			Files.writeString(file, root.toString());
		} catch (Exception e) {
			Bubble.LOG.warn("Could not save {}", file, e);
		}
	}
}
