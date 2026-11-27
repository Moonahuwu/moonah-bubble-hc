package moonahmc.bubble.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import moonahmc.bubble.WheelAdmin;
import moonahmc.bubble.client.net.ClientPlayNetworking;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import static moonahmc.bubble.client.BubbleScreen.ACCENT;
import static moonahmc.bubble.client.BubbleScreen.CARD;
import static moonahmc.bubble.client.BubbleScreen.DANGER;
import static moonahmc.bubble.client.BubbleScreen.MUTED;
import static moonahmc.bubble.client.BubbleScreen.TEXT;
import static moonahmc.bubble.client.BubbleScreen.panel;
import static moonahmc.bubble.client.BubbleScreen.roundRect;

/**
 * Ops (Bubble menu > Admin > Wheel slices / Random events): switch slices and events on or off, change their weights
 * (how likely they are), do a slice right now, or force an event, announced or silent. Saved on the server straight away.
 */
public final class WheelAdminScreen extends ScaledScreen {
	private static final int W = 470;
	private static final int H = 380;
	private static final int ROW_H = 24;
	private static final String[] TABS = {"Start wheel", "Mid-run wheel", "Events"};
	private static final int WARN = 0xFFFFC107;
	/** Move button width (+4 gap). */
	private static final int MOVE_W = 52;

	private final Screen parent;
	private int tab;
	private int left;
	private int top;
	private int scroll;
	private JsonObject data;
	private long dataAt;
	private long armedAt;
	private int armedRow = -1;
	private int armedMove = -1;
	private long armedMoveAt;

	WheelAdminScreen(Screen parent, int tab) {
		super(Component.literal("Wheel and events"));
		this.parent = parent;
		this.tab = tab;
		ClientPlayNetworking.send(new WheelAdmin.Edit("request", "", 0, 0));
	}

	@Override
	protected int panelWidth() {
		return W;
	}

	@Override
	protected int panelHeight() {
		return H;
	}

	void refresh() {
		rebuildWidgets();
	}

	private void parse() {
		data = null;
		if (ClientState.wheelAdmin != null) {
			try {
				data = JsonParser.parseString(ClientState.wheelAdmin).getAsJsonObject();
				dataAt = Util.getMillis();
			} catch (Exception ignored) {
				data = null;
			}
		}
	}

	private JsonArray rows() {
		if (data == null) {
			return new JsonArray();
		}
		return switch (tab) {
			case 0 -> data.getAsJsonArray("start");
			case 1 -> data.getAsJsonArray("mid");
			default -> data.getAsJsonObject("events").getAsJsonArray("list");
		};
	}

	private JsonObject events() {
		return data == null ? null : data.getAsJsonObject("events");
	}

	private String list() {
		return tab == 1 ? "mid" : "start";
	}

	private int listTop() {
		return top + (tab == 2 ? 136 : 70);
	}

	private int visibleRows() {
		return (top + H - 40 - listTop()) / ROW_H;
	}

	private void send(String what, int index, int value) {
		ClientPlayNetworking.send(new WheelAdmin.Edit(what, list(), index, value));
	}

	@Override
	protected void init() {
		fit();
		left = (width - W) / 2;
		top = (height - H) / 2;
		parse();
		for (int i = 0; i < TABS.length; i++) {
			int t = i;
			int tw = 92;
			BubbleScreen.FlatButton b = addRenderableWidget(new BubbleScreen.FlatButton(left + 16 + i * (tw + 4), top + 38, tw, 16, Component.literal(TABS[i]), () -> {
				if (tab != t) {
					tab = t;
					scroll = 0;
					armedRow = -1;
					armedMove = -1;
					rebuildWidgets();
				}
			}));
			b.ghost = tab != i;
			b.selected = tab == i;
		}
		addRenderableWidget(new BubbleScreen.FlatButton(left + W - 14 - 64, top + H - 30, 64, 20, Component.literal("Back"), this::onClose)).ghost = true;
		if (data == null) {
			return;
		}
		if (tab == 2) {
			initEventControls();
		}
		JsonArray rows = rows();
		scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visibleRows())));
		for (int i = scroll; i < rows.size() && i < scroll + visibleRows(); i++) {
			JsonObject r = rows.get(i).getAsJsonObject();
			int index = i;
			int y = listTop() + (i - scroll) * ROW_H;
			int x = left + W - 16;
			boolean on = r.get("enabled").getAsBoolean();
			int weight = r.get("weight").getAsInt();
			// right to left: action buttons, toggle, weight
			if (tab == 2) {
				x -= 46;
				BubbleScreen.FlatButton silent = addRenderableWidget(new BubbleScreen.FlatButton(x, y + 3, 46, 16, Component.literal("Silent"),
						() -> send("event_force", index, 0)));
				silent.ghost = true;
				silent.setTooltip(Tooltip.create(Component.literal("Do it now without telling anyone")));
				x -= 50;
				BubbleScreen.FlatButton force = addRenderableWidget(new BubbleScreen.FlatButton(x, y + 3, 46, 16, Component.literal("Force"),
						() -> send("event_force", index, 1)));
				force.color = DANGER;
				force.setTooltip(Tooltip.create(Component.literal("Do it now, announced to everyone")));
			} else {
				x -= 54;
				BubbleScreen.FlatButton doIt = addRenderableWidget(new BubbleScreen.FlatButton(x, y + 3, 54, 16,
						Component.literal(armedRow == index && Util.getMillis() - armedAt < 3000 ? "Sure?" : "Do it"), null));
				doIt.color = DANGER;
				doIt.setAction(() -> {
					long now = Util.getMillis();
					if (armedRow != index || now - armedAt > 3000) {
						armedRow = index;
						armedAt = now;
						doIt.setMessage(Component.literal("Sure?"));
						return;
					}
					armedRow = -1;
					doIt.setMessage(Component.literal("Do it"));
					send("slice_apply", index, 0);
				});
				doIt.setTooltip(Tooltip.create(Component.literal("Does this slice for everyone right now (no spin). Click twice.")));
				x -= MOVE_W + 4;
				String moveWarn = r.has("moveWarn") ? r.get("moveWarn").getAsString() : "";
				String to = tab == 0 ? "mid-run" : "start";
				boolean armed = armedMove == index && Util.getMillis() - armedMoveAt < 4000;
				BubbleScreen.FlatButton move = addRenderableWidget(new BubbleScreen.FlatButton(x, y + 3, MOVE_W, 16,
						Component.literal(armed ? "Sure?" : tab == 0 ? "\u2192 Mid" : "\u2192 Start"), null));
				move.ghost = !armed;
				if (!moveWarn.isEmpty()) {
					move.color = WARN;
				}
				move.setAction(() -> {
					long now = Util.getMillis();
					if (!moveWarn.isEmpty() && (armedMove != index || now - armedMoveAt > 4000)) {
						armedMove = index;
						armedMoveAt = now;
						rebuildWidgets();
						return;
					}
					armedMove = -1;
					send("slice_move", index, 0);
				});
				move.setTooltip(Tooltip.create(Component.literal("Moves it to the " + to + " wheel.")
						.append(moveWarn.isEmpty() ? Component.empty()
								: Component.literal("\n\n\u26A0 On the " + to + " wheel: " + moveWarn + "\nClick twice.").withColor(WARN))));
			}
			x -= 40;
			addRenderableWidget(new BubbleScreen.Toggle(x, y + 2, () -> on, v -> send(tab == 2 ? "event_enabled" : "slice_enabled", index, v ? 1 : 0)));
			x -= 20;
			BubbleScreen.FlatButton plus = addRenderableWidget(new BubbleScreen.FlatButton(x, y + 3, 16, 16, Component.literal("+"),
					() -> send(tab == 2 ? "event_weight" : "slice_weight", index, weight + 1)));
			plus.ghost = true;
			x -= 44;
			BubbleScreen.FlatButton minus = addRenderableWidget(new BubbleScreen.FlatButton(x, y + 3, 16, 16, Component.literal("-"),
					() -> send(tab == 2 ? "event_weight" : "slice_weight", index, Math.max(0, weight - 1))));
			minus.ghost = true;
			minus.setTooltip(Tooltip.create(Component.literal("Weight: how likely it is (0 = never)")));
		}
	}

	private void initEventControls() {
		JsonObject ev = events();
		int y = top + 62;
		addRenderableWidget(new BubbleScreen.Toggle(left + 24, y, () -> ev.get("enabled").getAsBoolean(), v -> send("events_enabled", 0, v ? 1 : 0)));
		addRenderableWidget(new BubbleScreen.Toggle(left + 150, y, () -> ev.get("announce").getAsBoolean(), v -> send("events_announce", 0, v ? 1 : 0)));
		// with the director on these are the minutes of calm it waits for; off, the timer's minutes
		boolean calmMode = ev.has("director") && ev.get("director").getAsBoolean() && ev.has("calmMin");
		String lo = calmMode ? "events_calm_min" : "events_min";
		String hi = calmMode ? "events_calm_max" : "events_max";
		int min = calmMode ? ev.get("calmMin").getAsInt() : ev.get("min").getAsInt();
		int max = calmMode ? ev.get("calmMax").getAsInt() : ev.get("max").getAsInt();
		int bx = left + 262;
		ghost(bx, y + 1, "-", () -> send(lo, 0, Math.max(1, min - 1)));
		ghost(bx + 44, y + 1, "+", () -> send(lo, 0, Math.min(max, min + 1)));
		ghost(bx + 90, y + 1, "-", () -> send(hi, 0, Math.max(min, max - 1)));
		ghost(bx + 134, y + 1, "+", () -> send(hi, 0, max + 1));
		if (calmMode) {
			// the director's dials: gift share, gift after a fight, gap between events
			int ry = y + 46;
			int gift = ev.get("gift").getAsInt(), fight = ev.get("fightGift").getAsInt(), gap = ev.get("gap").getAsInt();
			ghost(left + 64, ry, "-", () -> send("events_gift", 0, Math.max(0, gift - 5)));
			ghost(left + 108, ry, "+", () -> send("events_gift", 0, Math.min(100, gift + 5)));
			ghost(left + 222, ry, "-", () -> send("events_fight_gift", 0, Math.max(0, fight - 10)));
			ghost(left + 266, ry, "+", () -> send("events_fight_gift", 0, Math.min(100, fight + 10)));
			ghost(left + 334, ry, "-", () -> send("events_gap", 0, Math.max(0, gap - 15)));
			ghost(left + 386, ry, "+", () -> send("events_gap", 0, Math.min(1800, gap + 15)));
		}
		boolean dir = ev.has("director") && ev.get("director").getAsBoolean();
		BubbleScreen.FlatButton director = addRenderableWidget(new BubbleScreen.FlatButton(left + W - 16 - 46 - 4 - 110 - 4 - 92, y + 24, 92, 16,
				Component.literal(dir ? "Director: on" : "Director: off"), () -> send("events_director", 0, dir ? 0 : 1)));
		director.ghost = !dir;
		director.setTooltip(Tooltip.create(Component.literal(
				"On: events come when the group has been quiet for a while (3-6 min of calm), not during fights, with a gift after a "
						+ "big fight now and then. Off: on the timer (the minutes above).")));
		BubbleScreen.FlatButton any = addRenderableWidget(new BubbleScreen.FlatButton(left + W - 16 - 46 - 4 - 110, y + 24, 110, 16,
				Component.literal("Random one now"), () -> send("event_force", -1, 1)));
		any.color = DANGER;
		BubbleScreen.FlatButton anySilent = addRenderableWidget(new BubbleScreen.FlatButton(left + W - 16 - 46, y + 24, 46, 16,
				Component.literal("Silent"), () -> send("event_force", -1, 0)));
		anySilent.ghost = true;
	}

	private void ghost(int x, int y, String label, Runnable action) {
		BubbleScreen.FlatButton b = addRenderableWidget(new BubbleScreen.FlatButton(x, y, 16, 16, Component.literal(label), action));
		b.ghost = true;
	}

	@Override
	protected boolean scrolled(double mx, double my, double h, double v) {
		int before = scroll;
		scroll = Math.max(0, Math.min(scroll - (int) Math.signum(v), Math.max(0, rows().size() - visibleRows())));
		if (scroll != before) {
			rebuildWidgets();
			return true;
		}
		return super.scrolled(mx, my, h, v);
	}

	@Override
	public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
		super.renderBackground(ctx, mouseX, mouseY, delta);
		ctx.fill(0, 0, width, height, 0x55000000);
		panel(ctx, left, top, W, H);
		BubbleScreen.bigText(ctx, font, tab == 2 ? "Random events" : "Wheel slices", left + 16, top + 14);
		if (data == null) {
			ctx.drawString(font, Component.literal("Loading..."), left + 16, top + 70, MUTED, false);
			return;
		}
		JsonArray rows = rows();
		hover = null;
		if (tab == 2) {
			JsonObject ev = events();
			int y = top + 62;
			ctx.drawString(font, Component.literal("Random events"), left + 62, y + 5, TEXT, false);
			ctx.drawString(font, Component.literal("Announce"), left + 188, y + 5, TEXT, false);
			int bx = left + 262;
			boolean calmMode = ev.has("director") && ev.get("director").getAsBoolean() && ev.has("calmMin");
			ctx.drawString(font, Component.literal(String.valueOf(ev.get(calmMode ? "calmMin" : "min").getAsInt())), bx + 22, y + 5, ACCENT, false);
			ctx.drawString(font, Component.literal("to"), bx + 66, y + 5, MUTED, false);
			ctx.drawString(font, Component.literal(String.valueOf(ev.get(calmMode ? "calmMax" : "max").getAsInt())), bx + 112, y + 5, ACCENT, false);
			ctx.drawString(font, Component.literal(calmMode ? "min calm" : "min"), bx + 156, y + 5, MUTED, false);
			if (calmMode) {
				int ry = y + 46;
				ctx.drawString(font, Component.literal("Gifts"), left + 24, ry + 4, TEXT, false);
				ctx.drawString(font, Component.literal(ev.get("gift").getAsInt() + "%"), left + 84, ry + 4, ACCENT, false);
				ctx.drawString(font, Component.literal("After a fight"), left + 140, ry + 4, TEXT, false);
				ctx.drawString(font, Component.literal(ev.get("fightGift").getAsInt() + "%"), left + 242, ry + 4, ACCENT, false);
				ctx.drawString(font, Component.literal("Gap"), left + 308, ry + 4, TEXT, false);
				ctx.drawString(font, Component.literal(ev.get("gap").getAsInt() + "s"), left + 354, ry + 4, ACCENT, false);
			}
			int next = ev.get("next").getAsInt();
			boolean dir = ev.has("director") && ev.get("director").getAsBoolean();
			String when = !ev.get("enabled").getAsBoolean() ? "Random events are off"
					: dir ? (ev.has("directorStatus") ? ev.get("directorStatus").getAsString() : "Director on")
					: next < 0 ? "Next one: once the run starts"
					: "Next one in about " + RunClock.fmt(Math.max(0, next - (int) ((Util.getMillis() - dataAt) / 1000)));
			when = font.plainSubstrByWidth(when, W - 16 - 46 - 4 - 110 - 4 - 92 - 24 - 8);
			ctx.drawString(font, Component.literal(when), left + 24, y + 29, MUTED, false);
		} else {
			long on = 0;
			for (var r : rows) {
				if (r.getAsJsonObject().get("enabled").getAsBoolean() && r.getAsJsonObject().get("weight").getAsInt() > 0) {
					on++;
				}
			}
			ctx.drawString(font, Component.literal(on + " of " + rows.size() + " slices are on the " + (tab == 0 ? "start-of-run" : "mid-run") + " wheel"),
					left + 16, top + 60 - 4, MUTED, false);
		}
		for (int i = scroll; i < rows.size() && i < scroll + visibleRows(); i++) {
			JsonObject r = rows.get(i).getAsJsonObject();
			int y = listTop() + (i - scroll) * ROW_H;
			boolean on = r.get("enabled").getAsBoolean();
			roundRect(ctx, left + 12, y, W - 24, ROW_H - 2, 5, CARD);
			int nx = left + 22;
			if (r.has("color")) {
				roundRect(ctx, nx, y + 5, 10, 12, 3, r.get("color").getAsInt() | 0xFF000000);
				nx += 16;
			}
			String warn = r.has("warn") ? r.get("warn").getAsString() : "";
			if (!warn.isEmpty()) {
				ctx.drawString(font, Component.literal("\u26A0"), nx, y + 7, WARN, false);
				nx += 11;
			}
			String full = r.get("name").getAsString();
			String name = font.plainSubstrByWidth(full, 190 - (warn.isEmpty() ? 0 : 11));
			if (!name.equals(full)) {
				name = font.plainSubstrByWidth(full, font.width(name) - font.width("...")) + "...";
			}
			ctx.drawString(font, Component.literal(name), nx, y + 7, on ? TEXT : MUTED, false);
			if (mouseX >= left + 12 && mouseX < nx + font.width(name) && mouseY >= y && mouseY < y + ROW_H - 2 && (!warn.isEmpty() || !name.equals(full))) {
				hover = new java.util.ArrayList<>(font.split(Component.literal(full), 260));
				if (!warn.isEmpty()) {
					hover.addAll(font.split(Component.literal("\u26A0 " + warn).withColor(WARN), 260));
				}
			}
			int weightX = left + W - 16 - (tab == 2 ? 96 : 54 + MOVE_W + 4) - 40 - 20 - 44;
			String w = String.valueOf(r.get("weight").getAsInt());
			ctx.drawString(font, Component.literal(w), weightX + 30 - font.width(w) / 2, y + 7, ACCENT, false);
		}
		if (rows.size() > visibleRows()) {
			String more = (scroll + 1) + "-" + Math.min(rows.size(), scroll + visibleRows()) + " of " + rows.size() + " (scroll)";
			ctx.drawString(font, Component.literal(more), left + 16, top + H - 24, MUTED, false);
		} else {
			ctx.drawString(font, Component.literal(tab == 2 ? "Weight = how likely. Force/Silent do it now." : "Weight = how likely (0 = never). \u2192 moves it to the other wheel."),
					left + 16, top + H - 24, MUTED, false);
		}
	}

	/** Hover text for a row's name (its full name, and what works differently about it on this wheel). */
	private java.util.List<net.minecraft.util.FormattedCharSequence> hover;

	@Override
	protected void renderScaled(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
		super.renderScaled(ctx, mouseX, mouseY, delta);
		if (hover != null) {
			ctx.renderTooltip(font, hover, mouseX, mouseY);
		}
	}

	@Override
	public void onClose() {
		if (minecraft != null) {
			minecraft.setScreen(parent);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** m:ss */
	private static final class RunClock {
		static String fmt(int s) {
			return (s / 60) + ":" + String.format("%02d", s % 60);
		}
	}
}
