package moonahmc.bubble.client;

import moonahmc.bubble.Payloads;
import moonahmc.bubble.ShopPayloads;
import net.minecraft.Util;
import net.minecraft.client.multiplayer.ServerData;

/** The latest things the server sent. `available` is false until we're on a server that runs Bubble. */
public final class ClientState {
	public static boolean available;
	public static boolean bubbleEnabled = true;
	public static boolean pushMode;
	public static boolean wrapMode;
	public static boolean soulLink;
	public static boolean hardcoreReset = true;
	public static boolean vertical;
	public static int radius = 50;
	public static boolean canEdit;
	public static int players;
	public static int run = 1;

	// forcefield (smoothed every client tick, interpolated every frame)
	public static boolean fieldActive;
	static boolean fieldSphere;
	static double targetX;
	static double targetY;
	static double targetZ;
	static double targetR;
	static double fieldX;
	static double fieldY;
	static double fieldZ;
	static double fieldR;
	static double prevX;
	static double prevY;
	static double prevZ;
	static double prevR;
	static long pulseAt;

	// post-game
	public static Payloads.PostGame postGame;
	public static Payloads.PostStatus postStatus;
	/** The server we were on when the run ended, so the disconnect screen can offer "Rejoin". */
	static ServerData lastServer;

	// Strange Matter + shop
	static boolean hasBalance;
	static double balance;
	static ShopPayloads.Catalog catalog;
	/** The Admin tab's status card lines (AdminStatus). */
	static java.util.List<String> adminStatus = java.util.List.of();
	/** When the catalog arrived (System.currentTimeMillis), to count the shop window down. */
	static long catalogAt;
	static ShopPayloads.ChargeList charges;
	static ShopPayloads.QuoteResult quote;

	private ClientState() {
	}

	static void update(Payloads.State s) {
		available = true;
		bubbleEnabled = s.bubbleEnabled();
		pushMode = s.pushMode();
		wrapMode = s.wrapMode();
		soulLink = s.soulLink();
		hardcoreReset = s.hardcoreReset();
		vertical = s.vertical();
		radius = s.radius();
		canEdit = s.canEdit();
		players = s.players();
		run = s.run();
	}

	static void field(Payloads.Field f) {
		if (!f.active()) {
			fieldActive = false;
			return;
		}
		targetX = f.centerX();
		targetY = f.centerY();
		targetZ = f.centerZ();
		targetR = f.radius();
		if (!fieldActive || fieldSphere != f.sphere()) {
			fieldX = prevX = targetX;
			fieldY = prevY = targetY;
			fieldZ = prevZ = targetZ;
			fieldR = prevR = targetR;
			fieldActive = true;
		}
		fieldSphere = f.sphere();
		if (f.pulse()) {
			pulseAt = Util.getMillis();
		}
	}

	/** Glides the drawn field towards the server's numbers so it doesn't jump four times a second. */
	static void tickField() {
		prevX = fieldX;
		prevY = fieldY;
		prevZ = fieldZ;
		prevR = fieldR;
		if (fieldActive) {
			fieldX += (targetX - fieldX) * 0.3;
			fieldY += (targetY - fieldY) * 0.3;
			fieldZ += (targetZ - fieldZ) * 0.3;
			fieldR += (targetR - fieldR) * 0.3;
		}
	}

	/** "Next run" tab settings (null until the server sends them). */
	static moonahmc.bubble.PregamePayloads.State pregame;
	/** Ready-up lobby (null or inactive when there's none). */
	static moonahmc.bubble.Lobby.State lobby;
	/** Run goals for the HUD. */
	static moonahmc.bubble.Goals.State goals;
	/** The goals config for the op editor. */
	static moonahmc.bubble.Goals.Config goalsConfig;
	static String wheelAdmin;
	/** Cases for the op case editor. */
	static moonahmc.bubble.Gamble.CaseList caseList;
	/** Your Eidolons, upgrades, cosmetics and the hall of fame. */
	static moonahmc.bubble.Eidolons.State eidolons;
	/** The betting book (ready screen). */
	static moonahmc.bubble.Eidolons.Bets bets;

	static void reset() {
		pregame = null;
		lobby = null;
		goals = null;
		goalsConfig = null;
		wheelAdmin = null;
		caseList = null;
		eidolons = null;
		bets = null;
		available = false;
		fieldActive = false;
		postGame = null;
		postStatus = null;
		hasBalance = false;
		balance = 0;
		catalog = null;
		charges = null;
		quote = null;
	}
}
