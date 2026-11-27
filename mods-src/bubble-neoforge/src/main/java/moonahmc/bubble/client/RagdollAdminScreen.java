package moonahmc.bubble.client;

import moonahmc.bubble.RagdollTuning;
import moonahmc.bubble.client.net.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import static moonahmc.bubble.client.BubbleScreen.CARD;
import static moonahmc.bubble.client.BubbleScreen.MUTED;
import static moonahmc.bubble.client.BubbleScreen.TEXT;
import static moonahmc.bubble.client.BubbleScreen.panel;
import static moonahmc.bubble.client.BubbleScreen.roundRect;

/**
 * Ops (Bubble menu > Admin > Ragdolls): how fragile everyone is. A preset row (Sturdy / Normal / Fragile / Glass), then
 * each number and switch on its own. Changes apply to the ragdoll mods straight away.
 */
final class RagdollAdminScreen extends ScaledScreen {
	private static final int W = 520;
	private static final int H = 356;
	private static final String[] PRESETS = {"Sturdy", "Normal", "Fragile", "Glass"};
	private static final double[] SPEED = {4, 8, 12, 16, 24, 32, 48};
	private static final double[] HIT = {1, 2, 4, 6, 8, 12, 20};
	private static final double[] FALL = {0.5, 1, 2, 4, 6, 10};
	private static final double[] FLING = {0.5, 0.75, 1, 1.5, 2, 2.5, 3, 5};
	private static final double[] COOLDOWN = {0, 1, 2, 3, 5, 10};
	private static final double[] IMPACT = {0.25, 0.5, 0.75, 1, 1.5, 2};
	static RagdollTuning.Settings settings;

	private final Screen parent;
	private int left;
	private int top;

	RagdollAdminScreen(Screen parent) {
		super(Component.literal("Ragdolls"));
		this.parent = parent;
		ClientPlayNetworking.send(new RagdollTuning.Edit(true, new RagdollTuning.Settings(false, false, false, false, 0, 0, 0, 0, 0, false, false, 0, false, false)));
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

	private static String[] names(double[] values, String unit) {
		String[] out = new String[values.length];
		for (int i = 0; i < values.length; i++) {
			double v = values[i];
			out[i] = (v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v)) + unit;
		}
		return out;
	}

	private static int nearest(double[] values, double v) {
		int best = 0;
		for (int i = 1; i < values.length; i++) {
			if (Math.abs(values[i] - v) < Math.abs(values[best] - v)) {
				best = i;
			}
		}
		return best;
	}

	private static void send(RagdollTuning.Settings s) {
		settings = s;
		ClientPlayNetworking.send(new RagdollTuning.Edit(false, s));
	}

	/** A copy with one field changed (by position in the list below). */
	private static RagdollTuning.Settings with(int field, double num, boolean flag) {
		RagdollTuning.Settings s = settings;
		return new RagdollTuning.Settings(s.sable(), s.reactionsMod(), s.fragile(),
				field == 0 ? flag : s.reactions(),
				field == 1 ? num : s.impactSpeed(),
				field == 2 ? num : s.hitDamage(),
				field == 3 ? num : s.fallDamage(),
				field == 4 ? num : s.fling(),
				field == 5 ? (int) num : s.cooldownSeconds(),
				field == 6 ? flag : s.mobsReact(),
				field == 7 ? flag : s.impactDamage(),
				field == 8 ? num : s.impactDamageMultiplier(),
				field == 9 ? flag : s.grab(),
				field == 10 ? flag : s.manualKey());
	}

	private int rowY(int i) {
		return top + 72 + i * 34;
	}

	@Override
	protected void init() {
		fit();
		left = (width - W) / 2;
		top = (height - H) / 2;
		addRenderableWidget(new BubbleScreen.FlatButton(left + W - 14 - 64, top + H - 30, 64, 20, Component.literal("Back"), this::onClose)).ghost = true;
		RagdollTuning.Settings s = settings;
		if (s == null) {
			return;
		}
		for (int i = 0; i < PRESETS.length; i++) {
			int which = i;
			BubbleScreen.FlatButton b = addRenderableWidget(new BubbleScreen.FlatButton(left + 120 + i * 74, top + 42, 70, 18,
					Component.literal(PRESETS[i]), () -> send(settings.withPreset(which))));
			b.ghost = true;
			b.active = s.reactionsMod();
		}
		int lx = left + 14 + 250 - 108;
		choice(lx, rowY(0), SPEED, " m/s", 1, () -> settings.impactSpeed(), s.reactionsMod());
		choice(lx, rowY(1), HIT, "", 2, () -> settings.hitDamage(), s.reactionsMod());
		choice(lx, rowY(2), FALL, "", 3, () -> settings.fallDamage(), s.reactionsMod());
		choice(lx, rowY(3), FLING, "x", 4, () -> settings.fling(), s.reactionsMod());
		choice(lx, rowY(4), COOLDOWN, " s", 5, () -> settings.cooldownSeconds(), s.reactionsMod() || s.sable());
		choice(lx, rowY(5), IMPACT, "x", 8, () -> settings.impactDamageMultiplier(), s.sable());
		int rx = left + W - 22 - 34;
		toggle(rx, rowY(0), 0, () -> settings.reactions(), s.reactionsMod());
		toggle(rx, rowY(1), 6, () -> settings.mobsReact(), s.reactionsMod());
		toggle(rx, rowY(2), 7, () -> settings.impactDamage(), s.sable());
		toggle(rx, rowY(3), 9, () -> settings.grab(), s.sable());
		toggle(rx, rowY(4), 10, () -> settings.manualKey(), s.sable());
	}

	private void choice(int x, int y, double[] values, String unit, int field, java.util.function.DoubleSupplier get, boolean on) {
		BubbleScreen.Choice c = addRenderableWidget(new BubbleScreen.Choice(x, y + 9, 100, 16, names(values, unit),
				() -> settings == null ? 0 : nearest(values, get.getAsDouble()),
				v -> send(with(field, values[v], false))));
		c.active = on;
	}

	private void toggle(int x, int y, int field, java.util.function.BooleanSupplier get, boolean on) {
		BubbleScreen.Toggle t = addRenderableWidget(new BubbleScreen.Toggle(x, y + 8, () -> settings != null && get.getAsBoolean(),
				v -> send(with(field, 0, v))));
		t.active = on;
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
		BubbleScreen.bigText(ctx, font, "Ragdolls", left + 22, top + 14);
		RagdollTuning.Settings s = settings;
		if (s == null) {
			ctx.drawString(font, "Loading...", left + 24, top + 50, MUTED, false);
			return;
		}
		if (!s.sable() && !s.reactionsMod()) {
			ctx.drawString(font, "Sable Ragdolls / Ragdoll Reactions aren't installed.", left + 24, top + 50, MUTED, false);
			return;
		}
		ctx.drawString(font, "Presets", left + 24, top + 47, TEXT, false);
		if (s.fragile()) {
			ctx.drawString(font, "Fragile mode (wheel) is on: Glass until it ends", left + 120, top + 62, 0xFFFFB74D, false);
		}
		String[][] leftRows = {
				{"Ragdoll from a sudden stop", "speed change (lower = more fragile)"},
				{"Ragdoll from a hit", "damage of at least (half-hearts)"},
				{"Ragdoll from a fall / crash", "damage of at least (half-hearts)"},
				{"Fling strength", "how far hits and blasts throw you"},
				{"Ragdoll cooldown", "before it can happen again"},
				{"Ragdoll impact damage", "how much hard landings hurt"}};
		for (int i = 0; i < leftRows.length; i++) {
			int y = rowY(i);
			roundRect(ctx, left + 14, y, 250, 30, 6, CARD);
			ctx.drawString(font, leftRows[i][0], left + 22, y + 5, TEXT, false);
			ctx.drawString(font, font.plainSubstrByWidth(leftRows[i][1], 120), left + 22, y + 17, MUTED, false);
		}
		String[][] rightRows = {
				{"Players react", "ragdoll from hits, falls, blasts"},
				{"Mobs react", "mobs ragdoll from them too"},
				{"Landings hurt", "ragdolls take impact damage"},
				{"Grab ragdolls", "pick up and drag bodies"},
				{"Ragdoll key", "players can flop on purpose"}};
		for (int i = 0; i < rightRows.length; i++) {
			int y = rowY(i);
			int x = left + 276;
			roundRect(ctx, x, y, W - 276 - 14, 30, 6, CARD);
			ctx.drawString(font, rightRows[i][0], x + 8, y + 5, TEXT, false);
			ctx.drawString(font, rightRows[i][1], x + 8, y + 17, MUTED, false);
		}
		ctx.drawString(font, "Changes apply straight away. Wheel: Fragile mode = Glass for 3 min.", left + 24, top + H - 24, MUTED, false);
	}
}
