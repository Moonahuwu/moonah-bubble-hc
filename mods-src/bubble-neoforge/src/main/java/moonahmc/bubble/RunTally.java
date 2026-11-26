package moonahmc.bubble;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Two small things for the group:
 * <ul>
 * <li><b>Downs and deaths this run</b>, after each name in the tab list ("✚3 ☠1"). Kept in the world folder
 * (bubble-tally.json), so a restart mid-run keeps it, "Another life" carries on counting, and a new world (or a
 * retry) starts from nothing.</li>
 * <li><b>One team</b> ("bubble", a normal scoreboard team) with friendly fire on: you can still hit and kill each
 * other, but weapons and abilities that check who's on your side (area attacks, homing shots, summons, pets) leave
 * your friends alone. Players already on another team are left there. /bubble team on|off.</li>
 * </ul>
 */
final class RunTally {
	static final String TEAM = "bubble";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** player -> {downs, deaths} */
	private static final Map<UUID, int[]> counts = new HashMap<>();
	private static MinecraftServer server;
	private static Path file;

	private RunTally() {
	}

	static void init() {
		var bus = NeoForge.EVENT_BUS;
		bus.addListener((ServerStartedEvent e) -> {
			server = e.getServer();
			file = server.getWorldPath(LevelResource.ROOT).resolve("bubble-tally.json");
			load();
			applyTeam();
		});
		bus.addListener((ServerStoppedEvent e) -> {
			server = null;
			counts.clear();
		});
		bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
			if (e.getEntity() instanceof ServerPlayer p) {
				join(p);
			}
		});
		// last, so it goes after the Eidolon title and name colour
		bus.addListener(EventPriority.LOWEST, (PlayerEvent.TabListNameFormat e) -> {
			Component suffix = suffix(e.getEntity().getUUID());
			if (suffix == null) {
				return;
			}
			Component base = e.getDisplayName() != null ? e.getDisplayName() : e.getEntity().getName();
			e.setDisplayName(Component.empty().append(base).append(suffix));
		});
	}

	private static Component suffix(UUID id) {
		int[] c = counts.get(id);
		if (c == null || (c[0] == 0 && c[1] == 0)) {
			return null;
		}
		MutableComponent s = Component.literal(" ");
		if (c[0] > 0) {
			s.append(Component.literal(" ✚" + c[0]).withStyle(ChatFormatting.YELLOW));
		}
		if (c[1] > 0) {
			s.append(Component.literal(" ☠" + c[1]).withStyle(ChatFormatting.RED));
		}
		return s;
	}

	private static boolean counting() {
		return server != null && BubbleServer.playing() && !Lobby.active();
	}

	/** Revive: `p` just went down. */
	static void down(ServerPlayer p) {
		add(p, 0);
	}

	/** `p` just died. */
	static void death(ServerPlayer p) {
		add(p, 1);
	}

	private static void add(ServerPlayer p, int which) {
		if (!counting() || p instanceof net.neoforged.neoforge.common.util.FakePlayer) {
			return;
		}
		counts.computeIfAbsent(p.getUUID(), k -> new int[2])[which]++;
		save();
		p.refreshTabListName();
	}

	/** Downs and deaths so far this run (for /bubble status and the like). */
	static int[] of(UUID id) {
		int[] c = counts.get(id);
		return c == null ? new int[2] : c.clone();
	}

	// ------------------------------------------------------------------ the team

	private static void join(ServerPlayer p) {
		if (server == null || !BubbleServer.config.teamUp) {
			return;
		}
		Scoreboard sb = server.getScoreboard();
		PlayerTeam team = team(sb);
		if (sb.getPlayersTeam(p.getScoreboardName()) == null) {
			sb.addPlayerToTeam(p.getScoreboardName(), team);
		}
	}

	private static PlayerTeam team(Scoreboard sb) {
		PlayerTeam team = sb.getPlayerTeam(TEAM);
		if (team == null) {
			team = sb.addPlayerTeam(TEAM);
			team.setDisplayName(Component.literal("Bubble"));
		}
		team.setAllowFriendlyFire(true); // you can still hurt each other
		team.setSeeFriendlyInvisibles(true);
		team.setNameTagVisibility(Team.Visibility.ALWAYS);
		team.setCollisionRule(Team.CollisionRule.ALWAYS);
		return team;
	}

	/** On: everyone not on another team joins it. Off: the team goes. */
	static void applyTeam() {
		if (server == null) {
			return;
		}
		Scoreboard sb = server.getScoreboard();
		if (BubbleServer.config.teamUp) {
			team(sb);
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				join(p);
			}
		} else {
			PlayerTeam team = sb.getPlayerTeam(TEAM);
			if (team != null) {
				sb.removePlayerTeam(team);
			}
		}
	}

	// ------------------------------------------------------------------ saving

	private static void load() {
		counts.clear();
		if (file == null || !Files.exists(file)) {
			return;
		}
		try {
			JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			for (var e : o.entrySet()) {
				var a = e.getValue().getAsJsonArray();
				counts.put(UUID.fromString(e.getKey()), new int[] {a.get(0).getAsInt(), a.get(1).getAsInt()});
			}
		} catch (Exception ex) {
			Bubble.LOG.warn("Could not read {}", file, ex);
		}
	}

	private static void save() {
		if (file == null) {
			return;
		}
		JsonObject o = new JsonObject();
		counts.forEach((id, c) -> {
			var a = new com.google.gson.JsonArray();
			a.add(c[0]);
			a.add(c[1]);
			o.add(id.toString(), a);
		});
		try {
			Files.writeString(file, GSON.toJson(o));
		} catch (Exception ex) {
			Bubble.LOG.warn("Could not save {}", file, ex);
		}
	}
}
