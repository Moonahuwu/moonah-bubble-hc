package moonahmc.bubble.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import moonahmc.bubble.Wheel;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * The start-of-run and mid-run wheels, drawn over the game (you can still move). It spins for the time the server says, slowing
 * down onto the slice the server picked, ticking as each slice passes the pointer, then shows the result for a moment.
 */
final class WheelOverlay {
	private static final long SHOW_RESULT_MS = 3500;
	private static Wheel.Spin spin;
	private static long startMs;
	private static long spinMs;
	private static int lastSlice = -1;
	private static boolean dinged;

	private WheelOverlay() {
	}

	static void start(Wheel.Spin s) {
		spin = s;
		startMs = Util.getMillis();
		spinMs = s.ticks() * 50L - 300;
		lastSlice = -1;
		dinged = false;
	}

	static void stop() {
		spin = null;
	}

	/** Wheel angle in degrees (clockwise) at time t (0..1): 6 full turns, easing out, ending with the result under the pointer. */
	private static double angle(double t) {
		int n = spin.names().size();
		double slice = 360.0 / n;
		double end = 360 * 6 + (360 - (spin.result() + 0.5) * slice);
		double eased = 1 - Math.pow(1 - t, 3);
		return end * eased;
	}

	static void render(GuiGraphics ctx) {
		if (spin == null || spin.names().isEmpty()) {
			return;
		}
		long now = Util.getMillis() - startMs;
		if (now > spinMs + SHOW_RESULT_MS) {
			spin = null;
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		int w = ctx.guiWidth();
		int h = ctx.guiHeight();
		float r = Math.min(90, Math.min(w, h) * 0.3f);
		float cx = w / 2f;
		float cy = h / 2f - 10;
		double t = Mth.clamp(now / (double) spinMs, 0, 1);
		double rot = angle(t);
		int n = spin.names().size();
		double slice = 360.0 / n;

		// Tick as slices pass the pointer, ding at the end
		int under = (int) Math.floor(((360 - (rot % 360)) % 360) / slice) % n;
		if (under != lastSlice && t < 1) {
			lastSlice = under;
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.6f, 0.25f));
		}
		if (t >= 1 && !dinged) {
			dinged = true;
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0f, 0.6f));
		}

		ctx.flush();
		ctx.fill(0, 0, w, h, 0x40000000);
		ctx.flush();
		Matrix4f m = ctx.pose().last().pose();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		disc(m, cx, cy, r + 5, 0xFF0B0D10);
		for (int i = 0; i < n; i++) {
			double a0 = rot + i * slice - 90;
			int color = spin.colors().get(i);
			boolean winner = t >= 1 && i == spin.result() && (now / 250) % 2 == 0;
			wedge(m, cx, cy, r, a0, a0 + slice, winner ? brighten(color) : color);
		}
		disc(m, cx, cy, r * 0.16f, 0xFF0B0D10);
		disc(m, cx, cy, r * 0.12f, 0xFFF2C14E);
		// pointer at the top
		BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		buf.addVertex(m, cx - 9, cy - r - 12, 0).setColor(0xFFFFFFFF);
		buf.addVertex(m, cx, cy - r + 6, 0).setColor(0xFFFFFFFF);
		buf.addVertex(m, cx + 9, cy - r - 12, 0).setColor(0xFFFFFFFF);
		BufferUploader.drawWithShader(buf.buildOrThrow());
		RenderSystem.disableBlend();

		Font font = mc.font;
		if (n <= 10) {
			for (int i = 0; i < n; i++) {
				double mid = Math.toRadians(rot + (i + 0.5) * slice - 90);
				float lx = cx + (float) Math.cos(mid) * r * 0.6f;
				float ly = cy + (float) Math.sin(mid) * r * 0.6f;
				String label = font.plainSubstrByWidth(spin.names().get(i), (int) (r * 0.9f));
				ctx.drawString(font, label, (int) (lx - font.width(label) / 2f), (int) (ly - 4), 0xFFFFFFFF, true);
			}
		} else {
			// Lots of slices: write each name along its slice, from the hub out to the rim, smaller as the wheel fills up
			float textScale = Mth.clamp((float) (r * Math.toRadians(slice)) / 10f, 0.45f, 0.8f);
			float inner = r * 0.2f, outer = r * 0.95f;
			int maxW = (int) ((outer - inner) / textScale);
			for (int i = 0; i < n; i++) {
				double midDeg = rot + (i + 0.5) * slice - 90;
				double norm = ((midDeg % 360) + 360) % 360;
				boolean leftSide = norm > 90 && norm < 270; // flip these so the text isn't upside down
				String label = font.plainSubstrByWidth(spin.names().get(i), maxW);
				ctx.pose().pushPose();
				ctx.pose().translate(cx, cy, 0);
				ctx.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) (leftSide ? midDeg + 180 : midDeg)));
				ctx.pose().scale(textScale, textScale, 1);
				float x = leftSide ? -outer / textScale : inner / textScale;
				ctx.drawString(font, label, (int) (leftSide ? x + (maxW - font.width(label)) : x), -4, 0xFFFFFFFF, true);
				ctx.pose().popPose();
			}
		}
		String title = t < 1 ? spin.title() : spin.names().get(spin.result()) + "!";
		ctx.pose().pushPose();
		ctx.pose().translate(cx, cy + r + 14, 0);
		float scale = t < 1 ? 1.2f : 1.8f;
		ctx.pose().scale(scale, scale, 1);
		ctx.drawString(font, Component.literal(title), -font.width(title) / 2, 0, t < 1 ? 0xFFE6E9EE : spin.colors().get(spin.result()) | 0xFF000000, true);
		ctx.pose().popPose();
	}

	private static int brighten(int c) {
		int r = Math.min(255, ((c >> 16) & 255) + 70);
		int g = Math.min(255, ((c >> 8) & 255) + 70);
		int b = Math.min(255, (c & 255) + 70);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static void disc(Matrix4f m, float cx, float cy, float r, int color) {
		wedge(m, cx, cy, r, 0, 360, color);
	}

	private static void wedge(Matrix4f m, float cx, float cy, float r, double fromDeg, double toDeg, int color) {
		int steps = Math.max(2, (int) Math.ceil((toDeg - fromDeg) / 6));
		BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
		buf.addVertex(m, cx, cy, 0).setColor(color);
		// Fan vertices go anticlockwise on screen (y points down), so walk the angle backwards.
		for (int s = steps; s >= 0; s--) {
			double a = Math.toRadians(fromDeg + (toDeg - fromDeg) * s / steps);
			buf.addVertex(m, cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, 0).setColor(color);
		}
		MeshData mesh = buf.build();
		if (mesh != null) {
			BufferUploader.drawWithShader(mesh);
		}
	}
}
