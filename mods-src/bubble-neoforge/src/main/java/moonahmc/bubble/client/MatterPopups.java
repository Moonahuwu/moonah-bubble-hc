package moonahmc.bubble.client;

import moonahmc.bubble.Matter;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * "+125" floating up from your Strange Matter balance whenever it changes: selling, kills, rewards (green), buying
 * (red). Quick changes in a row add up into one number that bumps each time, so killing a swarm counts up "+1, +2,
 * +3...". Shown next to the balance in the inventory and the shop, and above the hotbar when no screen is open.
 */
final class MatterPopups {
	private static final long LIFE_MS = 1700;
	private static final long MERGE_MS = 1200;
	private static final int GAIN = 0xFF7DF0C8;
	private static final int LOSS = 0xFFEF7D7D;

	private static final class Popup {
		double amount;
		long start;
		long bump;

		Popup(double amount, long now) {
			this.amount = amount;
			this.start = now;
			this.bump = now;
		}
	}

	private static final List<Popup> popups = new ArrayList<>();

	private MatterPopups() {
	}

	/** The balance went from `before` to `after`. */
	static void changed(double before, double after) {
		double d = after - before;
		if (Math.abs(d) < 0.5) {
			return;
		}
		long now = Util.getMillis();
		Popup last = popups.isEmpty() ? null : popups.get(popups.size() - 1);
		if (last != null && Math.signum(last.amount) == Math.signum(d) && now - last.bump < MERGE_MS) {
			last.amount += d;
			last.bump = now;
			last.start = Math.max(last.start, now - 250); // stay fully visible while it keeps counting
		} else {
			popups.add(new Popup(d, now));
			if (popups.size() > 4) {
				popups.remove(0);
			}
		}
	}

	private static float easeOutBack(float t) {
		t = Math.max(0f, Math.min(1f, t));
		float c1 = 1.70158f;
		float c3 = c1 + 1f;
		return 1f + c3 * (t - 1) * (t - 1) * (t - 1) + c1 * (t - 1) * (t - 1);
	}

	private static float easeOut(float t) {
		t = Math.max(0f, Math.min(1f, t));
		return 1f - (1f - t) * (1f - t) * (1f - t);
	}

	/** Draws the popups ending at `right`, rising from `y`. */
	static void render(GuiGraphics ctx, Font font, int right, int y) {
		long now = Util.getMillis();
		Iterator<Popup> it = popups.iterator();
		int stack = 0;
		List<Popup> live = new ArrayList<>();
		while (it.hasNext()) {
			Popup p = it.next();
			if (now - p.start > LIFE_MS) {
				it.remove();
			} else {
				live.add(p);
			}
		}
		for (int i = live.size() - 1; i >= 0; i--) {
			Popup p = live.get(i);
			float t = (now - p.start) / (float) LIFE_MS;
			float pop = easeOutBack((now - p.start) / 260f);
			float bump = 1f + 0.22f * (1f - easeOut((now - p.bump) / 220f));
			float scale = (0.55f + 0.45f * pop) * bump;
			float rise = easeOut(t) * 14f;
			float alpha = t < 0.65f ? 1f : Math.max(0f, 1f - (t - 0.65f) / 0.35f);
			if (alpha <= 0.03f) {
				continue;
			}
			String text = (p.amount > 0 ? "+" : "-") + Matter.format(Math.abs(Math.round(p.amount)));
			// gains shimmer while they're fresh, losses just fade
			int color = p.amount > 0 ? Rarity.pulse(GAIN, 0xFFFFFFFF, 0.7f) : LOSS;
			int a = Math.max(5, Math.round(alpha * 255)) << 24;
			int w = font.width(text) + 12;
			ctx.pose().pushPose();
			ctx.pose().translate(right - w / 2f, y - rise - stack * 12, 400);
			ctx.pose().scale(scale, scale, 1f);
			// soft pill behind it
			BubbleScreen.roundRect(ctx, -w / 2 - 3, -2, w + 6, 12, 5, (Math.round(alpha * 0x70) << 24));
			com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
			BubbleScreen.matterIcon(ctx, -w / 2, -1, 9);
			com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			ctx.drawString(font, text, -w / 2 + 12, 0, a | (color & 0xFFFFFF), true);
			ctx.pose().popPose();
			stack++;
		}
	}

	/** Above the hotbar's right end while playing (no screen open). */
	static void renderHud(GuiGraphics ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (popups.isEmpty() || mc.screen != null || mc.options.hideGui || mc.player == null) {
			return;
		}
		render(ctx, mc.font, ctx.guiWidth() / 2 + 91, ctx.guiHeight() - 64);
	}
}
