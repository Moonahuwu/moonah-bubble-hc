package moonahmc.bubble.client;

import moonahmc.bubble.Revive;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import static moonahmc.bubble.client.BubbleScreen.CARD;
import static moonahmc.bubble.client.BubbleScreen.MUTED;
import static moonahmc.bubble.client.BubbleScreen.TEXT;
import static moonahmc.bubble.client.BubbleScreen.panel;
import static moonahmc.bubble.client.BubbleScreen.roundRect;

/**
 * Ops (Bubble menu > Admin > Rules): everything about going down and getting back up, in one place. Changes apply
 * straight away (the server tells everyone).
 */
final class RulesScreen extends ScaledScreen {
	private static final int W = 420;
	private static final int H = 292;
	private static final int[] BLEED = {10, 15, 20, 30, 45, 60, 90, 120, 180};
	private static final int[] HOLD = {0, 1, 2, 3, 5, 8};
	private static final int[] COOLDOWN = {0, 60, 120, 180, 300, 600, 900};
	private static final String[] COOLDOWN_NAMES = {"None", "1 min", "2 min", "3 min", "5 min", "10 min", "15 min"};
	private static final int[] FALL = {0, 15, 20, 25, 30, 40, 60};
	private static final String[] FALL_NAMES = {"Never", "15+", "20+", "25+", "30+", "40+", "60+"};

	private final Screen parent;
	private int left;
	private int top;

	RulesScreen(Screen parent) {
		super(Component.literal("Rules"));
		this.parent = parent;
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

	private static String[] names(int[] v, String unit, String zero) {
		String[] out = new String[v.length];
		for (int i = 0; i < v.length; i++) {
			out[i] = v[i] == 0 && zero != null ? zero : v[i] + unit;
		}
		return out;
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

	private int rowY(int i) {
		return top + 44 + i * 36;
	}

	@Override
	protected void init() {
		fit();
		left = (width - W) / 2;
		top = (height - H) / 2;
		addRenderableWidget(new BubbleScreen.FlatButton(left + W - 14 - 64, top + H - 30, 64, 20, Component.literal("Back"), this::onClose)).ghost = true;
		Revive.Settings s = ReviveClient.settings;
		if (s == null) {
			return;
		}
		int x = left + W - 22;
		addRenderableWidget(new BubbleScreen.Toggle(x - 34, rowY(0) + 8, () -> ReviveClient.settings != null && ReviveClient.settings.enabled(),
				v -> ReviveClient.send(v, null, null, null, null, null)));
		choice(x - 96, rowY(1), names(BLEED, " s", null), () -> nearest(BLEED, ReviveClient.settings.seconds()),
				v -> ReviveClient.send(null, BLEED[v], null, null, null, null));
		choice(x - 96, rowY(2), names(HOLD, " s", "Instant"), () -> nearest(HOLD, ReviveClient.settings.holdSeconds()),
				v -> ReviveClient.send(null, null, HOLD[v], null, null, null));
		choice(x - 96, rowY(3), COOLDOWN_NAMES, () -> nearest(COOLDOWN, ReviveClient.settings.cooldown()),
				v -> ReviveClient.send(null, null, null, COOLDOWN[v], null, null));
		choice(x - 96, rowY(4), FALL_NAMES, () -> nearest(FALL, ReviveClient.settings.fall()),
				v -> ReviveClient.send(null, null, null, null, FALL[v], null));
		addRenderableWidget(new BubbleScreen.Toggle(x - 34, rowY(5) + 8, () -> ReviveClient.settings != null && ReviveClient.settings.ragdoll(),
				v -> ReviveClient.send(null, null, null, null, null, v)));
	}

	private void choice(int x, int y, String[] names, java.util.function.IntSupplier get, java.util.function.IntConsumer set) {
		BubbleScreen.Choice c = addRenderableWidget(new BubbleScreen.Choice(x, y + 9, 96, 16, names,
				() -> ReviveClient.settings == null ? 0 : get.getAsInt(), set));
		c.active = ReviveClient.settings != null && ReviveClient.settings.enabled();
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
		BubbleScreen.bigText(ctx, font, "Rules", left + 22, top + 14);
		if (ReviveClient.settings == null) {
			ctx.drawString(font, "Loading...", left + 24, top + 50, MUTED, false);
			return;
		}
		String[][] rows = {
				{"Revive", "Go down instead of dying; a teammate gets you up"},
				{"Bleed out after", "How long you have while you're down"},
				{"Getting someone up takes", "Standing right next to them"},
				{"Revive cooldown", "After a revive; going down again in it is a death"},
				{"Falls that kill outright", "Blocks fallen (no going down)"},
				{"Ragdoll when down", "Go limp (Sable Ragdolls) until revived"}};
		for (int i = 0; i < rows.length; i++) {
			int y = rowY(i);
			roundRect(ctx, left + 14, y, W - 28, 32, 6, CARD);
			ctx.drawString(font, rows[i][0], left + 24, y + 6, TEXT, false);
			ctx.drawString(font, font.plainSubstrByWidth(rows[i][1], W - 28 - 130), left + 24, y + 18, MUTED, false);
		}
		ctx.drawString(font, "Lava and the Watcher's stare always kill outright.", left + 22, top + H - 24, MUTED, false);
	}
}
