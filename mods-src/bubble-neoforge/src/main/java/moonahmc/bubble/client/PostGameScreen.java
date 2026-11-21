package moonahmc.bubble.client;

import moonahmc.bubble.Payloads;
import moonahmc.bubble.client.net.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntFunction;

import static moonahmc.bubble.client.BubbleScreen.ACCENT;
import static moonahmc.bubble.client.BubbleScreen.CARD;
import static moonahmc.bubble.client.BubbleScreen.DANGER;
import static moonahmc.bubble.client.BubbleScreen.MUTED;
import static moonahmc.bubble.client.BubbleScreen.TEXT;
import static moonahmc.bubble.client.BubbleScreen.roundRect;

/** End-of-run summary: highlights, everyone's stats, and a vote on what happens next (with a 60 second timer). */
public final class PostGameScreen extends ScaledScreen {
	@Override
	protected int panelWidth() {
		return W;
	}

	@Override
	protected int panelHeight() {
		return H;
	}

	private static final int W = 440;
	private static final int H = 312;
	private static final String[] VOTE_LABELS = {"Next world", "Retry seed", "Another life"};
	private static final int[] VOTE_COLORS = {ACCENT, 0xFF7FB2F0, BubbleScreen.SOUL};
	private static final int ROW_H = 13;
	private static final int VISIBLE_ROWS = 6;

	private static final String[] COLUMNS = {"Player", "Mined", "Kills", "Dealt", "Taken", "Travelled", "Crafted"};
	private static final int[] COLUMN_X = {0, 118, 162, 206, 250, 300, 360};

	private int left;
	private int top;
	private int scroll;
	/** Stats table: which column it's sorted by (kept between runs) and which way. */
	private static int sortCol = 1;
	private static boolean sortAsc;
	/** false = the stats table, true = "What happened" (the run log). */
	private boolean showLog;
	private int logScroll;
	private static final int LOG_ROW = 12;
	private final BubbleScreen.FlatButton[] voteButtons = new BubbleScreen.FlatButton[3];
	private BubbleScreen.FlatButton seedButton;

	public PostGameScreen() {
		super(Component.literal("Run summary"));
	}

	@Override
	protected void init() {
		fit();
		left = (width - W) / 2;
		top = (height - H) / 2;
		BubbleScreen.FlatButton hide = addRenderableWidget(new BubbleScreen.FlatButton(left + 14, top + H - 30, 48, 20,
				Component.literal("Hide"), this::onClose));
		hide.ghost = true;
		int bw = (W - 28 - 54 - 12) / 3;
		for (int i = 0; i < 3; i++) {
			int option = i;
			BubbleScreen.FlatButton b = addRenderableWidget(new BubbleScreen.FlatButton(left + 14 + 54 + i * (bw + 6), top + H - 30, bw, 20,
					Component.literal(VOTE_LABELS[i]), () -> {
						// Ops: shift-click decides it for everyone right away
						if (ClientState.canEdit && hasShiftDown()) {
							ClientPlayNetworking.send(new moonahmc.bubble.Lobby.Admin(moonahmc.bubble.BubbleServer.ADMIN_DECIDE + option));
						} else {
							ClientPlayNetworking.send(new Payloads.Vote(option));
						}
					}));
			if (ClientState.canEdit) {
				b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Shift-click (op): decide this for everyone now")));
			}
			b.ghost = true;
			b.color = VOTE_COLORS[i];
			voteButtons[i] = b;
		}
		voteButtons[0].setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("A brand new world with a new seed")));
		voteButtons[1].setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("A fresh copy of this same world (same seed)")));
		voteButtons[2].setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
				"Keep this world: everyone goes back to how they were just before the death, with full health")));
		seedButton = addRenderableWidget(new BubbleScreen.FlatButton(left + W - 14 - 112, top + H - 56, 112, 18, Component.literal("☆ Save seed"),
				() -> ClientPlayNetworking.send(new Payloads.SaveSeed())));
		seedButton.ghost = true;
		seedButton.color = 0xFFF2C14E;
		seedButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
				"Vote to keep this seed in the favourites list (/bubble seeds)")));
		Payloads.PostGame g = ClientState.postGame;
		int logs = g == null ? 0 : g.log().size();
		String logLabel = "What happened" + (logs > 0 ? " (" + logs + ")" : "");
		int lw = font.width(logLabel) + 16;
		BubbleScreen.FlatButton logTab = addRenderableWidget(new BubbleScreen.FlatButton(left + W - 14 - lw, top + 50, lw, 16, Component.literal(logLabel), () -> {
			showLog = true;
			rebuildWidgets();
		}));
		logTab.ghost = !showLog;
		logTab.selected = showLog;
		logTab.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
				"What the wheels landed on, Wheel Spins and random events this run")));
		BubbleScreen.FlatButton statsTab = addRenderableWidget(new BubbleScreen.FlatButton(left + W - 14 - lw - 4 - 46, top + 50, 46, 16, Component.literal("Stats"), () -> {
			showLog = false;
			rebuildWidgets();
		}));
		statsTab.ghost = showLog;
		statsTab.selected = !showLog;
	}

	private int logTop() {
		return top + 72;
	}

	private int logRows() {
		return (top + 124 + 16 + VISIBLE_ROWS * ROW_H + 6 - logTop() - 10) / LOG_ROW;
	}

	/** The stats table's header: click a column to sort by it (again to flip the order). */
	@Override
	protected boolean clicked(double mx, double my, int button) {
		if (super.clicked(mx, my, button)) {
			return true;
		}
		int tx = left + 14;
		int ty = top + 124;
		if (!showLog && button == 0 && my >= ty + 2 && my < ty + 17) {
			for (int i = 0; i < COLUMNS.length; i++) {
				int from = tx + 12 + (i == 0 ? -12 : COLUMN_X[i] - 4);
				int to = i + 1 < COLUMNS.length ? tx + 12 + COLUMN_X[i + 1] - 4 : tx + W - 28;
				if (mx >= from && mx < to) {
					if (sortCol == i) {
						sortAsc = !sortAsc;
					} else {
						sortCol = i;
						sortAsc = i == 0;
					}
					scroll = 0;
					if (minecraft != null) {
						minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
								net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.2f, 0.4f));
					}
					return true;
				}
			}
		}
		return false;
	}

	private static Comparator<Payloads.PlayerStats> order(int col) {
		return switch (col) {
			case 0 -> Comparator.comparing((Payloads.PlayerStats p) -> p.name().toLowerCase());
			case 2 -> Comparator.comparingInt(Payloads.PlayerStats::mobKills);
			case 3 -> Comparator.comparingInt(Payloads.PlayerStats::damageDealt);
			case 4 -> Comparator.comparingInt(Payloads.PlayerStats::damageTaken);
			case 5 -> Comparator.comparingInt(Payloads.PlayerStats::distanceCm);
			case 6 -> Comparator.comparingInt(Payloads.PlayerStats::itemsCrafted);
			default -> Comparator.comparingInt(Payloads.PlayerStats::blocksMined);
		};
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected boolean scrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		Payloads.PostGame g = ClientState.postGame;
		if (g != null && showLog) {
			int max = Math.max(0, g.log().size() - logRows());
			logScroll = Math.max(0, Math.min(max, logScroll - (int) Math.signum(vertical)));
		} else if (g != null) {
			int max = Math.max(0, g.players().size() - VISIBLE_ROWS);
			scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(vertical)));
		}
		return true;
	}

	@Override
	protected void renderScaled(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
		Payloads.PostStatus st = ClientState.postStatus;
		for (int i = 0; i < 3; i++) {
			int n = st == null ? 0 : st.votes()[i];
			boolean mine = st != null && st.myVote() == i;
			voteButtons[i].selected = mine;
			voteButtons[i].setMessage(Component.literal((mine ? "✔ " : "") + VOTE_LABELS[i] + (n > 0 ? "  " + n : "")));
		}
		boolean saved = st != null && st.mySeedSave();
		int saves = st == null ? 0 : st.seedSaves();
		seedButton.selected = saved;
		seedButton.setMessage(Component.literal((saved ? "★ Seed saved" : "☆ Save seed") + (saves > 0 ? "  " + saves : "")));
		super.renderScaled(ctx, mouseX, mouseY, delta);
	}

	@Override
	public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
		super.renderBackground(ctx, mouseX, mouseY, delta);
		ctx.fill(0, 0, width, height, 0x66000000);
		BubbleScreen.panel(ctx, left, top, W, H);
		Payloads.PostGame g = ClientState.postGame;
		if (g == null) {
			return;
		}

		// Header
		BubbleScreen.bigText(ctx, font, "Run #" + g.run() + " complete", left + 22, top + 18);
		BubbleScreen.pill(ctx, font, left + W - 22, top + 20, "☠ GAME OVER", DANGER);
		String cause = font.plainSubstrByWidth(g.cause(), W - 44 - 30);
		ctx.drawString(font, Component.literal(cause), left + 22, top + 40, DANGER, false);
		ctx.drawString(font, Component.literal("Survived " + duration(g.durationTicks())), left + 22, top + 52, MUTED, false);

		if (showLog) {
			renderLog(ctx, g);
			renderVotes(ctx);
			return;
		}

		// Highlights
		int cardW = (W - 28 - 3 * 8) / 4;
		int y = top + 70;
		highlight(ctx, g.players(), left + 14, y, cardW, "Most mined", Payloads.PlayerStats::blocksMined, v -> num(v) + " blocks");
		highlight(ctx, g.players(), left + 14 + (cardW + 8), y, cardW, "Most kills", Payloads.PlayerStats::mobKills, v -> num(v) + " mobs");
		highlight(ctx, g.players(), left + 14 + 2 * (cardW + 8), y, cardW, "Most damage", Payloads.PlayerStats::damageDealt, v -> hearts(v) + " ❤");
		highlight(ctx, g.players(), left + 14 + 3 * (cardW + 8), y, cardW, "Furthest", Payloads.PlayerStats::distanceCm, PostGameScreen::distance);

		// Table
		int tx = left + 14;
		int ty = top + 124;
		int tw = W - 28;
		int th = 16 + VISIBLE_ROWS * ROW_H + 6;
		roundRect(ctx, tx, ty, tw, th, 6, CARD);
		for (int i = 0; i < COLUMNS.length; i++) {
			boolean sorted = i == sortCol;
			String label = sorted ? COLUMNS[i] + (sortAsc ? "▲" : "▼") : COLUMNS[i];
			int cx = i == 0 ? tx + 12 : tx + 12 + COLUMN_X[i] + (sorted ? 0 : 0);
			if (i == 0) {
				cell(ctx, label, cx, ty + 6, true, sorted ? TEXT : MUTED);
			} else {
				ctx.drawString(font, Component.literal(label), cx + 36 - font.width(COLUMNS[i]), ty + 6, sorted ? TEXT : MUTED, false);
			}
		}
		ctx.fill(tx + 8, ty + 17, tx + tw - 8, ty + 18, 0x14FFFFFF);
		List<Payloads.PlayerStats> rows = new ArrayList<>(g.players());
		rows.sort(sortAsc ? order(sortCol) : order(sortCol).reversed());
		String me = minecraft != null && minecraft.player != null ? minecraft.player.getName().getString() : "";
		for (int i = 0; i < VISIBLE_ROWS && i + scroll < rows.size(); i++) {
			Payloads.PlayerStats p = rows.get(i + scroll);
			int ry = ty + 22 + i * ROW_H;
			int color = p.name().equals(me) ? ACCENT : TEXT;
			String[] values = {p.name(), num(p.blocksMined()), num(p.mobKills()), hearts(p.damageDealt()), hearts(p.damageTaken()),
					distance(p.distanceCm()), num(p.itemsCrafted())};
			for (int c = 0; c < values.length; c++) {
				String v = c == 0 ? font.plainSubstrByWidth(values[c], 104) : values[c];
				cell(ctx, v, tx + 12 + COLUMN_X[c], ry, c == 0, c == 0 ? color : TEXT);
			}
		}
		if (rows.size() > VISIBLE_ROWS) {
			String more = (scroll + 1) + "-" + Math.min(rows.size(), scroll + VISIBLE_ROWS) + " of " + rows.size() + " (scroll)";
			ctx.drawString(font, Component.literal(more), tx + tw - 12 - font.width(more), ty + th - 11, MUTED, false);
		}

		renderVotes(ctx);
	}

	private void renderLog(GuiGraphics ctx, Payloads.PostGame g) {
		int tx = left + 14;
		int tw = W - 28;
		int ty = logTop();
		int th = logRows() * LOG_ROW + 10;
		roundRect(ctx, tx, ty, tw, th, 6, CARD);
		if (g.log().isEmpty()) {
			ctx.drawString(font, Component.literal("Nothing from the wheels or random events this run."), tx + 12, ty + 8, MUTED, false);
			return;
		}
		logScroll = Math.max(0, Math.min(logScroll, Math.max(0, g.log().size() - logRows())));
		for (int i = 0; i < logRows() && i + logScroll < g.log().size(); i++) {
			String[] parts = g.log().get(i + logScroll).split("	", 3);
			if (parts.length < 3) {
				continue;
			}
			int ry = ty + 6 + i * LOG_ROW;
			long t = 0;
			try {
				t = Long.parseLong(parts[0]);
			} catch (NumberFormatException ignored) {
			}
			String time = clock(t);
			ctx.drawString(font, Component.literal(time), tx + 10 + 40 - font.width(time), ry, MUTED, false);
			int color = switch (parts[1]) {
				case moonahmc.bubble.RunLog.WHEEL -> 0xFFF2C14E;
				case moonahmc.bubble.RunLog.SPIN -> 0xFFD36BFF;
				case moonahmc.bubble.RunLog.EVENT -> DANGER;
				default -> 0xFF7FB2F0;
			};
			roundRect(ctx, tx + 56, ry + 1, 6, 6, 2, color);
			ctx.drawString(font, Component.literal(font.plainSubstrByWidth(parts[2], tw - 80)), tx + 68, ry, TEXT, false);
		}
		if (g.log().size() > logRows()) {
			String more = (logScroll + 1) + "-" + Math.min(g.log().size(), logScroll + logRows()) + " of " + g.log().size() + " (scroll)";
			ctx.drawString(font, Component.literal(more), tx + tw - 10 - font.width(more), ty + th + 2, MUTED, false);
		}
	}

	/** Run time as h:mm:ss or m:ss. */
	private static String clock(long ticks) {
		long secs = ticks / 20;
		return secs >= 3600 ? String.format("%d:%02d:%02d", secs / 3600, secs / 60 % 60, secs % 60) : String.format("%d:%02d", secs / 60, secs % 60);
	}

	private void renderVotes(GuiGraphics ctx) {
		// Countdown + votes
		Payloads.PostStatus st = ClientState.postStatus;
		int secs = st == null ? 60 : st.secondsLeft();
		int by = top + H - 64;
		roundRect(ctx, left + 14, by, W - 28, 4, 2, 0xFF2E333B);
		int fill = (int) ((W - 28) * Math.max(0, Math.min(1, secs / 60.0)));
		roundRect(ctx, left + 14, by, fill, 4, 2, ACCENT);
		ctx.drawString(font, Component.literal("What next?  " + secs + "s"), left + 18, top + H - 53, TEXT, false);
		if (st != null) {
			int voted = st.votes()[0] + st.votes()[1] + st.votes()[2];
			String info = voted + "/" + st.total() + " voted" + (st.livesUsed() > 0 ? "  ·  lives used: " + st.livesUsed() : "");
			ctx.drawString(font, Component.literal(info), left + 18, top + H - 42, MUTED, false);
			String seed = "Seed " + st.seed();
			ctx.drawString(font, Component.literal(font.plainSubstrByWidth(seed, 150)), left + W - 14 - 112 - 8 - Math.min(150,
					font.width(seed)), top + H - 51, MUTED, false);
		}
	}

	private void cell(GuiGraphics ctx, String s, int x, int y, boolean leftAlign, int color) {
		// Number columns are right-aligned to the start of the next column.
		ctx.drawString(font, Component.literal(s), leftAlign ? x : x + 36 - font.width(s), y, color, false);
	}

	private void highlight(GuiGraphics ctx, List<Payloads.PlayerStats> players, int x, int y, int w, String label,
						   ToIntFunction<Payloads.PlayerStats> value, java.util.function.IntFunction<String> format) {
		roundRect(ctx, x, y, w, 46, 6, CARD);
		ctx.drawString(font, Component.literal(label), x + 9, y + 8, MUTED, false);
		Payloads.PlayerStats best = players.stream().max(Comparator.comparingInt(value)).orElse(null);
		if (best == null || value.applyAsInt(best) <= 0) {
			ctx.drawString(font, Component.literal("—"), x + 9, y + 21, TEXT, false);
			return;
		}
		ctx.drawString(font, Component.literal(font.plainSubstrByWidth(best.name(), w - 18)), x + 9, y + 21, TEXT, false);
		ctx.drawString(font, Component.literal(font.plainSubstrByWidth(format.apply(value.applyAsInt(best)), w - 18)), x + 9, y + 32, ACCENT, false);
	}

	static String num(int v) {
		return String.format("%,d", v);
	}

	/** Damage stats are stored in tenths of a health point; two points make a heart. */
	static String hearts(int tenths) {
		double h = tenths / 20.0;
		return h >= 100 ? String.format("%,d", Math.round(h)) : String.format("%.1f", h);
	}

	static String distance(int cm) {
		int blocks = cm / 100;
		return blocks >= 10_000 ? String.format("%.1f km", blocks / 1000.0) : String.format("%,d m", blocks);
	}

	static String duration(int ticks) {
		int secs = ticks / 20;
		int h = secs / 3600;
		int m = (secs % 3600) / 60;
		return h > 0 ? h + "h " + m + "m" : m + "m " + (secs % 60) + "s";
	}
}
