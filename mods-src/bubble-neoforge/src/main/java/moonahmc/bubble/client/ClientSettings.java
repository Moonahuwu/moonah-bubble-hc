package moonahmc.bubble.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import moonahmc.bubble.Bubble;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;

/** Your own Bubble settings (only affect what you see), saved in config/bubble-client.json. */
final class ClientSettings {
	static final int MIN_WALL = 4;
	static final int MAX_WALL = 64;
	/** Wall distance value meaning "always visible". */
	static final int ALWAYS = 65;
	static final int DEFAULT_WALL = 26;

	/** The forcefield starts to show when you're this close to the edge (blocks). */
	static int wallDistance = DEFAULT_WALL;
	/** Shop: items you starred (by item id), the sort, how many to buy, and the last tab you had open. */
	static final java.util.Set<String> favourites = new java.util.LinkedHashSet<>();
	static int shopSort;
	static int shopAmount;
	static String shopTab = "";
	static boolean shopGamble;
	/** Revive: play back how you went down when you go down (DownCam). */
	static boolean downCam = true;

	private ClientSettings() {
	}

	private static Path file() {
		return FMLPaths.CONFIGDIR.get().resolve("bubble-client.json");
	}

	static boolean wallAlways() {
		return wallDistance >= ALWAYS;
	}

	static void load() {
		try {
			if (Files.exists(file())) {
				JsonObject o = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
				if (o.has("wallDistance")) {
					wallDistance = Math.max(MIN_WALL, Math.min(ALWAYS, o.get("wallDistance").getAsInt()));
				}
				if (o.has("favourites")) {
					o.getAsJsonArray("favourites").forEach(x -> favourites.add(x.getAsString()));
				}
				shopSort = o.has("shopSort") ? Math.max(0, Math.min(3, o.get("shopSort").getAsInt())) : 0;
				shopAmount = o.has("shopAmount") ? Math.max(0, Math.min(3, o.get("shopAmount").getAsInt())) : 0;
				shopTab = o.has("shopTab") ? o.get("shopTab").getAsString() : "";
				shopGamble = o.has("shopGamble") && o.get("shopGamble").getAsBoolean();
				downCam = !o.has("downCam") || o.get("downCam").getAsBoolean();
			}
		} catch (Exception e) {
			Bubble.LOG.warn("Could not read config/bubble-client.json, using defaults", e);
		}
	}

	static void save() {
		try {
			JsonObject o = new JsonObject();
			o.addProperty("wallDistance", wallDistance);
			com.google.gson.JsonArray fav = new com.google.gson.JsonArray();
			favourites.forEach(fav::add);
			o.add("favourites", fav);
			o.addProperty("shopSort", shopSort);
			o.addProperty("shopAmount", shopAmount);
			o.addProperty("shopTab", shopTab);
			o.addProperty("shopGamble", shopGamble);
			o.addProperty("downCam", downCam);
			Files.createDirectories(file().getParent());
			Files.writeString(file(), o.toString());
		} catch (Exception e) {
			Bubble.LOG.warn("Could not save config/bubble-client.json", e);
		}
	}
}
