package moonahmc.bubble.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;

import static moonahmc.bubble.client.BubbleScreen.DANGER;
import static moonahmc.bubble.client.BubbleScreen.MUTED;
import static moonahmc.bubble.client.BubbleScreen.TEXT;
import static moonahmc.bubble.client.BubbleScreen.roundRect;

/** The Bubble-style pop-up in the top-right corner ("✔ Run started"); shows over any screen for a few seconds. */
final class NoticeToast implements Toast {
	private static final int W = 190;
	private static final long SHOW_MS = 3500;
	private static final int OK = 0xFF6EE08A;
	private final String title;
	private final String body;
	private final boolean ok;

	private NoticeToast(String title, String body, boolean ok) {
		this.title = title;
		this.body = body;
		this.ok = ok;
	}

	static void show(String title, String body, boolean ok) {
		Minecraft.getInstance().getToasts().addToast(new NoticeToast(title, body, ok));
	}

	@Override
	public int width() {
		return W;
	}

	@Override
	public int height() {
		return body.isEmpty() ? 22 : 32;
	}

	@Override
	public Visibility render(GuiGraphics ctx, ToastComponent toasts, long time) {
		var font = toasts.getMinecraft().font;
		roundRect(ctx, 0, 0, W, height(), 6, 0xF2121418);
		roundRect(ctx, 0, 4, 2, height() - 8, 1, ok ? OK : DANGER);
		String t = (ok ? "✔ " : "✖ ") + title;
		ctx.drawString(font, Component.literal(font.plainSubstrByWidth(t, W - 16)), 9, body.isEmpty() ? 7 : 6, ok ? TEXT : DANGER, false);
		if (!body.isEmpty()) {
			ctx.drawString(font, Component.literal(font.plainSubstrByWidth(body, W - 16)), 9, 18, MUTED, false);
		}
		return time >= SHOW_MS * toasts.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
	}
}
