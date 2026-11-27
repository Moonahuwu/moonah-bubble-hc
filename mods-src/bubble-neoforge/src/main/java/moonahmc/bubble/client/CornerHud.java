package moonahmc.bubble.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Two small timers just right of the hotbar: how long the shop window has left ("🛒 1:23") and your own revive
 * cooldown ("⌛ 4:12", you can't go down again until it's over).
 */
final class CornerHud {
	/** When your revive cooldown ends (System.currentTimeMillis), 0 = none. */
	static long reviveCooldownUntil;

	private CornerHud() {
	}

	private static String clock(long ms) {
		long secs = (ms + 999) / 1000;
		return secs / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", secs % 60);
	}

	static void render(GuiGraphics ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui || mc.player.isSpectator()) {
			return;
		}
		Font font = mc.font;
		int x = ctx.guiWidth() / 2 + 91 + 8;
		int y = ctx.guiHeight() - 19;
		int shop = ShopScreen.windowLeft();
		if (shop > 0) {
			long ms = shop * 50L;
			String s = "🛒 " + clock(ms);
			ctx.drawString(font, s, x, y, ms <= 10_000 ? Rarity.pulse(0xFFE06C5A, 0xFFFFC0B0, 2f) : 0xFF6CC5E0, true);
			y -= 11;
		}
		long cd = reviveCooldownUntil - System.currentTimeMillis();
		if (cd > 0) {
			ctx.drawString(font, "⌛ " + clock(cd), x, y, 0xFFB0B6C0, true);
		}
	}
}
