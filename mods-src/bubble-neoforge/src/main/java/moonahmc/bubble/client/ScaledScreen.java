package moonahmc.bubble.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A screen whose fixed-size panel always fits: when the window (at the current GUI scale) is smaller than the panel,
 * everything is drawn smaller and the mouse is converted to match. At sizes where it fits nothing changes.
 * Subclasses call {@link #fit()} first thing in init() and override renderScaled/clicked/scrolled instead of
 * render/mouseClicked/mouseScrolled.
 */
abstract class ScaledScreen extends Screen {
	protected float uiScale = 1f;
	/** Opening ease: the panel grows in from 96% and rises a few pixels over this long. */
	private static final long OPEN_MS = 170;
	private final long openedAt = net.minecraft.Util.getMillis();
	private int realW = -1;
	private int realH = -1;
	private int virtW = Integer.MIN_VALUE;
	private int virtH = Integer.MIN_VALUE;

	protected ScaledScreen(Component title) {
		super(title);
	}

	/** Size of the panel, including anything drawn around it. */
	protected abstract int panelWidth();

	protected abstract int panelHeight();

	/** Works out the scale and switches width/height to the scaled size. Call at the start of init(). */
	protected final void fit() {
		if (width != virtW || height != virtH) { // a real size from Minecraft (first init or a resize)
			realW = width;
			realH = height;
		}
		uiScale = Math.min(1f, Math.min(realW / (float) (panelWidth() + 16), realH / (float) (panelHeight() + 16)));
		width = virtW = Math.max(1, Math.round(realW / uiScale));
		height = virtH = Math.max(1, Math.round(realH / uiScale));
	}

	/** The window's real height at the current GUI scale (before any shrinking). Valid inside panelHeight() during fit(). */
	protected int realHeight() {
		return realH > 0 ? realH : height;
	}

	private double s(double v) {
		return v / uiScale;
	}

	@Override
	public final void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
		ctx.pose().pushPose();
		ctx.pose().scale(uiScale, uiScale, 1f);
		float t = Math.min(1f, (net.minecraft.Util.getMillis() - openedAt) / (float) OPEN_MS);
		if (t < 1f) {
			float e = 1f - (1f - t) * (1f - t) * (1f - t);
			float k = 0.96f + 0.04f * e;
			ctx.pose().translate(width / 2f, height / 2f + (1f - e) * 6f, 0);
			ctx.pose().scale(k, k, 1f);
			ctx.pose().translate(-width / 2f, -height / 2f, 0);
		}
		renderScaled(ctx, (int) s(mouseX), (int) s(mouseY), delta);
		ctx.pose().popPose();
	}

	protected void renderScaled(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
	}

	@Override
	public final boolean mouseClicked(double x, double y, int button) {
		return clicked(s(x), s(y), button);
	}

	protected boolean clicked(double x, double y, int button) {
		return super.mouseClicked(x, y, button);
	}

	@Override
	public final boolean mouseReleased(double x, double y, int button) {
		return super.mouseReleased(s(x), s(y), button);
	}

	@Override
	public final boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		return super.mouseDragged(s(x), s(y), button, s(dx), s(dy));
	}

	@Override
	public final boolean mouseScrolled(double x, double y, double h, double v) {
		return scrolled(s(x), s(y), h, v);
	}

	protected boolean scrolled(double x, double y, double h, double v) {
		return super.mouseScrolled(x, y, h, v);
	}

	@Override
	public void mouseMoved(double x, double y) {
		super.mouseMoved(s(x), s(y));
	}
}
