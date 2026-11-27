package moonahmc.bubble.client;

import moonahmc.bubble.ShopWindows;
import moonahmc.bubble.client.net.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static moonahmc.bubble.client.BubbleScreen.CARD;
import static moonahmc.bubble.client.BubbleScreen.MUTED;
import static moonahmc.bubble.client.BubbleScreen.TEXT;
import static moonahmc.bubble.client.BubbleScreen.panel;
import static moonahmc.bubble.client.BubbleScreen.roundRect;

/**
 * Ops (Bubble menu > Admin > Shop windows): whether the shop only opens at key moments of a run, how long each moment
 * opens it for, and which tabs stay open in between. Every change is sent straight away.
 */
final class ShopWindowsScreen extends ScaledScreen {
	private static final int W = 520;
	private static final int H = 404;
	private static final int[] SECONDS = {0, 30, 60, 90, 120, 180, 300, 600};
	private static final String[] SECONDS_NAMES = {"Off", "30 s", "1 min", "90 s", "2 min", "3 min", "5 min", "10 min"};
	private static final int[] CHESTS = {3, 5, 10, 15, 20, 30, 50};
	private static final String[] CHESTS_NAMES = {"3", "5", "10", "15", "20", "30", "50"};
	private static final String[] TRIGGERS = {"Run start", "New dimension", "Boss killed", "Elite / mini-boss", "Looter chests", "Stage cleared"};
	private static final String[] TRIGGER_WHO = {"everyone", "everyone", "everyone who helped", "the killer", "that player", "everyone (run goals)"};
	static ShopWindows.Settings settings;

	private final Screen parent;
	private int left;
	private int top;
	private int tabScroll;

	ShopWindowsScreen(Screen parent) {
		super(Component.literal("Shop windows"));
		this.parent = parent;
		ClientPlayNetworking.send(new ShopWindows.Edit(true, new ShopWindows.Settings(false, 0, 0, 0, 0, 0, 0, List.of(), List.of(), 0)));
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

	private static int nearest(int[] values, int v) {
		int best = 0;
		for (int i = 1; i < values.length; i++) {
			if (Math.abs(values[i] - v) < Math.abs(values[best] - v)) {
				best = i;
			}
		}
		return best;
	}

	private static int trigger(ShopWindows.Settings s, int i) {
		return switch (i) {
			case 0 -> s.start();
			case 1 -> s.dimension();
			case 2 -> s.boss();
			case 3 -> s.elite();
			case 4 -> s.chest();
			default -> s.goal();
		};
	}

	/** Send a changed copy (the server answers with the new settings). */
	private static void send(ShopWindows.Settings s) {
		settings = s;
		ClientPlayNetworking.send(new ShopWindows.Edit(false, s));
	}

	private static ShopWindows.Settings withTrigger(ShopWindows.Settings s, int i, int secs) {
		return new ShopWindows.Settings(s.enabled(), i == 0 ? secs : s.start(), i == 1 ? secs : s.dimension(), i == 2 ? secs : s.boss(),
				i == 3 ? secs : s.elite(), i == 4 ? secs : s.chest(), s.chests(), s.tabs(), s.open(), i == 5 ? secs : s.goal());
	}

	private int tabRows() {
		return (top + H - 44 - tabTop()) / 20;
	}

	private int tabTop() {
		return top + 74;
	}

	@Override
	protected void init() {
		fit();
		left = (width - W) / 2;
		top = (height - H) / 2;
		addRenderableWidget(new BubbleScreen.FlatButton(left + W - 14 - 64, top + H - 30, 64, 20, Component.literal("Back"), this::onClose)).ghost = true;
		ShopWindows.Settings s = settings;
		if (s == null) {
			return;
		}
		// left: on/off and the timings
		addRenderableWidget(new BubbleScreen.Toggle(left + 214, top + 46, () -> settings != null && settings.enabled(), v -> {
			ShopWindows.Settings c = settings;
			send(new ShopWindows.Settings(v, c.start(), c.dimension(), c.boss(), c.elite(), c.chest(), c.chests(), c.tabs(), c.open(), c.goal()));
		}));
		for (int i = 0; i < TRIGGERS.length; i++) {
			int index = i;
			BubbleScreen.Choice ch = addRenderableWidget(new BubbleScreen.Choice(left + 154, top + 92 + i * 40, 96, 16, SECONDS_NAMES,
					() -> settings == null ? 0 : nearest(SECONDS, trigger(settings, index)),
					v -> send(withTrigger(settings, index, SECONDS[v]))));
			ch.active = s.enabled();
		}
		BubbleScreen.Choice chests = addRenderableWidget(new BubbleScreen.Choice(left + 154, top + 92 + TRIGGERS.length * 40, 96, 16, CHESTS_NAMES,
				() -> settings == null ? 2 : nearest(CHESTS, settings.chests()),
				v -> {
					ShopWindows.Settings c = settings;
					send(new ShopWindows.Settings(c.enabled(), c.start(), c.dimension(), c.boss(), c.elite(), c.chest(), CHESTS[v], c.tabs(), c.open(), c.goal()));
				}));
		chests.active = s.enabled();
		// right: every tab, on = stays open between windows
		tabScroll = Math.max(0, Math.min(tabScroll, Math.max(0, s.tabs().size() - tabRows())));
		for (int r = 0; r < tabRows() && r + tabScroll < s.tabs().size(); r++) {
			int index = r + tabScroll;
			addRenderableWidget(new BubbleScreen.Toggle(left + W - 22 - 34, tabTop() + r * 20 + 1,
					() -> settings != null && index < settings.open().size() && settings.open().get(index), v -> {
				ShopWindows.Settings c = settings;
				List<Boolean> open = new ArrayList<>(c.open());
				open.set(index, v);
				send(new ShopWindows.Settings(c.enabled(), c.start(), c.dimension(), c.boss(), c.elite(), c.chest(), c.chests(), c.tabs(), open, c.goal()));
			}));
		}
	}

	@Override
	protected boolean scrolled(double x, double y, double h, double v) {
		if (settings == null || x < left + 276) {
			return false;
		}
		int max = Math.max(0, settings.tabs().size() - tabRows());
		int next = Math.max(0, Math.min(max, tabScroll - (int) Math.signum(v)));
		if (next != tabScroll) {
			tabScroll = next;
			rebuildWidgets();
		}
		return true;
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

	@Override
	public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
		super.renderBackground(ctx, mouseX, mouseY, delta);
		ctx.fill(0, 0, width, height, 0x55000000);
		panel(ctx, left, top, W, H);
		BubbleScreen.bigText(ctx, font, "Shop windows", left + 22, top + 14);
		ShopWindows.Settings s = settings;
		if (s == null) {
			ctx.drawString(font, "Loading...", left + 24, top + 50, MUTED, false);
			return;
		}
		// left column
		roundRect(ctx, left + 14, top + 40, 250, 30, 6, CARD);
		ctx.drawString(font, "Only open at key moments", left + 24, top + 46, TEXT, false);
		ctx.drawString(font, s.enabled() ? "On: closed in between" : "Off: the shop is always open", left + 24, top + 57, MUTED, false);
		for (int i = 0; i < TRIGGERS.length; i++) {
			int y = top + 80 + i * 40;
			roundRect(ctx, left + 14, y, 250, 36, 6, CARD);
			ctx.drawString(font, TRIGGERS[i], left + 24, y + 8, s.enabled() ? TEXT : MUTED, false);
			ctx.drawString(font, "for " + TRIGGER_WHO[i], left + 24, y + 20, MUTED, false);
		}
		int y = top + 80 + TRIGGERS.length * 40;
		roundRect(ctx, left + 14, y, 250, 36, 6, CARD);
		ctx.drawString(font, "Chests per window", left + 24, y + 8, s.enabled() ? TEXT : MUTED, false);
		ctx.drawString(font, "looter chests each", left + 24, y + 20, MUTED, false);
		// right column
		int rx = left + 276;
		ctx.drawString(font, "STAYS OPEN IN BETWEEN", rx, top + 46, MUTED, false);
		ctx.drawString(font, font.plainSubstrByWidth("Selling always works. Off = follows the windows.", W - 276 - 22), rx, top + 58, MUTED, false);
		for (int r = 0; r < tabRows() && r + tabScroll < s.tabs().size(); r++) {
			int index = r + tabScroll;
			int ty = tabTop() + r * 20;
			roundRect(ctx, rx, ty, W - 276 - 14, 18, 5, CARD);
			ctx.drawString(font, font.plainSubstrByWidth(s.tabs().get(index), W - 276 - 70), rx + 8, ty + 5, TEXT, false);
		}
		if (s.tabs().size() > tabRows()) {
			String more = (tabScroll + 1) + "-" + Math.min(s.tabs().size(), tabScroll + tabRows()) + " of " + s.tabs().size() + " (scroll)";
			ctx.drawString(font, more, rx, top + H - 24, MUTED, false);
		}
	}
}
