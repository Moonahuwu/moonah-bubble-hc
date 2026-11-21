package moonahmc.bubble.client;

import moonahmc.bubble.Goals;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The run goals in the top-right corner, Destiny style: the mode and stage as a small header, a thin rule, then the
 * objectives with square checkboxes and progress, then the bonus goals and any run modifiers (with their timers).
 * Sits under the potion icons when you have effects.
 *
 * It stays out of the way (minimaps live up there): it slides in for a few seconds when the team makes progress, with
 * the lines that changed highlighted, then fades out. The "Show/hide objectives" key (F4 by default) pins it open or
 * hides it again.
 */
final class GoalsHud {
	/** Pinned open with the key. */
	static boolean pinned;
	private static final long POP_MS = 8000;
	private static final long FADE_MS = 800;
	private static long popUntil;
	/** Lines whose progress changed in the last update (highlighted while it's showing). */
	private static final Set<String> changed = new HashSet<>();
	private static final int W = 176;
	private static final int GOLD = 0xFFE8C46A;
	private static final int WHITE = 0xFFF2F2F2;
	private static final int DIM = 0xFF9AA0A8;
	private static final int DONE = 0xFF7DD88A;
	private static final int SUB = 0xFF7FD6E8;

	private GoalsHud() {
	}

	/** The key: show it if it's hidden, hide it if it's showing. */
	static void toggle() {
		if (pinned || Util.getMillis() < popUntil) {
			pinned = false;
			popUntil = 0;
		} else {
			pinned = true;
		}
	}

	/** New goals from the server: pop up if something actually moved (not just modifier timers ticking). */
	static void update(Goals.State old, Goals.State now) {
		changed.clear();
		if (now == null || now.mode().isEmpty()) {
			return;
		}
		if (old == null || !old.mode().equals(now.mode()) || old.stage() != now.stage() || old.victory() != now.victory()
				|| !old.stageName().equals(now.stageName())) {
			now.objectives().forEach(l -> changed.add(l.text()));
			popUntil = Util.getMillis() + POP_MS;
			return;
		}
		diff(old.objectives(), now.objectives());
		diff(old.subgoals(), now.subgoals());
		if (!changed.isEmpty()) {
			popUntil = Util.getMillis() + POP_MS;
		}
	}

	private static void diff(List<Goals.Line> before, List<Goals.Line> after) {
		for (Goals.Line l : after) {
			Goals.Line was = before.stream().filter(b -> b.text().equals(l.text())).findFirst().orElse(null);
			if (was == null || was.done() != l.done() || (!l.done() && l.progress() > was.progress())) {
				changed.add(l.text());
			}
		}
	}

	/** 1 while showing, fading to 0 at the end of a pop-up. */
	private static float alpha() {
		if (pinned) {
			return 1f;
		}
		long left = popUntil - Util.getMillis();
		if (left <= 0) {
			return 0f;
		}
		return Math.min(1f, left / (float) FADE_MS);
	}

	private static int fade(int argb, float a) {
		int alpha = Math.round(((argb >>> 24) & 0xFF) * a);
		return (Math.max(alpha, 5) << 24) | (argb & 0xFFFFFF);
	}

	static void render(GuiGraphics ctx) {
		Minecraft mc = Minecraft.getInstance();
		Goals.State s = ClientState.goals;
		float a = alpha();
		if (a <= 0.03f || s == null || mc.player == null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()
				|| (s.mode().isEmpty() && s.modifiers().isEmpty())) {
			return;
		}
		Font font = mc.font;
		int right = ctx.guiWidth() - 6;
		int x = right - W;
		int y = 6;
		if (!mc.player.getActiveEffects().isEmpty()) {
			boolean harmful = mc.player.getActiveEffects().stream().anyMatch(e -> !e.getEffect().value().isBeneficial());
			boolean good = mc.player.getActiveEffects().stream().anyMatch(e -> e.getEffect().value().isBeneficial());
			y += (good ? 26 : 0) + (harmful ? 26 : 0);
		}
		// slide in from the right as it fades in/out
		int slide = Math.round((1f - a) * 40);
		ctx.pose().pushPose();
		ctx.pose().translate(slide, 0, 0);
		int lines = 3 + s.objectives().size() + (s.subgoals().isEmpty() ? 0 : 1 + s.subgoals().size()) + (s.modifiers().isEmpty() ? 0 : 1 + s.modifiers().size());
		int height = 10 + lines * 11 + 6;
		ctx.fillGradient(x - 6, y - 4, right + 2, y + height, fade(0x66000000, a), fade(0x22000000, a));

		if (!s.mode().isEmpty()) {
			// Header: MODE · 2/7
			String header = s.mode().toUpperCase() + "  ·  " + (s.victory() ? "COMPLETE" : s.stage() + "/" + s.stages());
			ctx.drawString(font, header, right - font.width(header), y, fade(GOLD, a), false);
			y += 11;
			ctx.pose().pushPose();
			ctx.pose().translate(right, y, 0);
			ctx.pose().scale(1.15f, 1.15f, 1f);
			String stage = s.victory() ? "Victory" : s.stageName();
			ctx.drawString(font, stage, -font.width(stage), 0, fade(WHITE, a), true);
			ctx.pose().popPose();
			y += 13;
			ctx.fill(x, y, right, y + 1, fade(0x80FFFFFF, a));
			y += 4;
			for (Goals.Line l : s.objectives()) {
				line(ctx, font, x, right, y, l, l.done() ? DONE : WHITE, a);
				y += 11;
			}
		}
		if (!s.subgoals().isEmpty()) {
			y += 3;
			ctx.drawString(font, "BONUS", right - font.width("BONUS"), y, fade(SUB, a), false);
			y += 10;
			for (Goals.Line l : s.subgoals()) {
				line(ctx, font, x, right, y, l, l.done() ? DONE : DIM, a);
				y += 11;
			}
		}
		if (!s.modifiers().isEmpty()) {
			y += 3;
			ctx.drawString(font, "MODIFIERS", right - font.width("MODIFIERS"), y, fade(0xFFD36BFF, a), false);
			y += 10;
			for (String m : s.modifiers()) {
				ctx.drawString(font, m, right - font.width(m), y, fade(0xFFE7C8FF, a), false);
				y += 11;
			}
		}
		ctx.pose().popPose();
	}

	/** "◻ Find 3 diamonds   1/3", right-aligned, with a filled box when done and a gold marker if it just changed. */
	private static void line(GuiGraphics ctx, Font font, int x, int right, int y, Goals.Line l, int color, float a) {
		String progress = l.target() > 1 && !l.done() ? "  " + l.progress() + "/" + l.target() : "";
		String text = font.plainSubstrByWidth(l.text(), W - 16 - font.width(progress));
		int tw = font.width(text + progress);
		int boxX = right - tw - 11;
		boolean fresh = !pinned && changed.contains(l.text());
		if (fresh) {
			ctx.fill(x - 4, y - 1, right + 1, y + 10, fade(0x40E8C46A, a));
			ctx.fill(x - 6, y - 1, x - 4, y + 10, fade(GOLD, a));
		}
		ctx.fill(boxX, y + 1, boxX + 7, y + 8, fade(0xFFE0E0E0, a));
		ctx.fill(boxX + 1, y + 2, boxX + 6, y + 7, fade(l.done() ? DONE : 0xFF202428, a));
		ctx.drawString(font, Component.literal(text), right - tw, y, fade(color, a), true);
		if (!progress.isEmpty()) {
			ctx.drawString(font, progress, right - font.width(progress), y, fade(GOLD, a), true);
		}
	}
}
