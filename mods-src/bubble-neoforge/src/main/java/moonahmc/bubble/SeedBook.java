package moonahmc.bubble;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Favourite seeds, voted for on the post-game screen. Kept in bubble-seeds.json next to the server (survives resets). */
public final class SeedBook {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static final class Saved {
		public String seed;
		public int run;
		public String date;
		public List<String> savedBy = new ArrayList<>();
	}

	private SeedBook() {
	}

	private static Path file() {
		return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("bubble-seeds.json");
	}

	static List<Saved> all() {
		try {
			if (Files.exists(file())) {
				List<Saved> list = GSON.fromJson(Files.readString(file()), new TypeToken<List<Saved>>() {
				}.getType());
				if (list != null) {
					list.removeIf(s -> s == null || s.seed == null);
					for (Saved s : list) {
						if (s.savedBy == null) {
							s.savedBy = new ArrayList<>();
						}
					}
					return list;
				}
			}
		} catch (Exception e) {
			Bubble.LOG.warn("bubble-seeds.json could not be read: {}", e.toString());
		}
		return new ArrayList<>();
	}

	private static void write(List<Saved> list) {
		try {
			Files.writeString(file(), GSON.toJson(list));
		} catch (Exception e) {
			Bubble.LOG.error("Could not save bubble-seeds.json", e);
		}
	}

	/** Adds or removes `player`'s vote for `seed`. Returns true if they now have it saved. */
	static boolean toggle(String seed, int run, String player) {
		List<Saved> list = all();
		Saved s = list.stream().filter(x -> x.seed.equals(seed)).findFirst().orElse(null);
		if (s == null) {
			s = new Saved();
			s.seed = seed;
			s.run = run;
			s.date = LocalDate.now().toString();
			list.add(s);
		}
		boolean now;
		if (s.savedBy.remove(player)) {
			now = false;
			if (s.savedBy.isEmpty()) {
				list.remove(s);
			}
		} else {
			s.savedBy.add(player);
			now = true;
		}
		write(list);
		return now;
	}

	static Saved find(String seed) {
		return all().stream().filter(x -> x.seed.equals(seed)).findFirst().orElse(null);
	}

	/** Most-voted first. */
	static List<Saved> top() {
		List<Saved> list = all();
		list.sort(Comparator.comparingInt((Saved s) -> s.savedBy.size()).reversed());
		return list;
	}
}
