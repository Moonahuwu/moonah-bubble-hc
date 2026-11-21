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
 * The start-of-run and mid-run wheels, drawn over the game (you can still move). The wheel sits on the left edge of
 * the screen with only its right half showing, big, and the pointer at 3 o'clock: every name is written along its
 * slice from the hub to the rim, so the one at the pointer reads left to right. Next to the pointer is the slice it's on
 * right now, and the result once it stops. It spins for the time the server says, slowing down onto the slice the
 * server picked, ticking as each slice passes the pointer.
 */
final class WheelOverlay {
	private static final long SHOW_RESULT_MS = 3500;
	private static final int RIM = 0xFF0B0D10;
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
		Font font = mc.font;
		int w = ctx.guiWidth();
		int h = ctx.guiHeight();
		// Centre on the left edge: only the right half shows, so it can be big
		float r = Math.min(Math.min(h * 0.47f, w * 0.42f), 190);
		float cx = 0;
		float cy = h / 2f;
		double t = Mth.clamp(now / (double) spinMs, 0, 1);
		double rot = angle(t);
		int n = spin.names().size();
		double slice = 360.0 / n;
		// The pointer is at 0 degrees (3 o'clock); slice i covers rot + i*slice .. + slice
		int under = Math.floorMod((int) Math.floor(((360 - (rot % 360)) % 360) / slice), n);
		if (under != lastSlice && t < 1) {
			lastSlice = under;
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.6f, 0.25f));
		}
		if (t >= 1 && !dinged) {
			dinged = true;
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0f, 0.6f));
		}

		ctx.flush();
		Matrix4f m = ctx.pose().last().pose();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		// Darken the left side behind the wheel and the readout, fading out to the right
		hGradient(m, 0, 0, Math.min(w, r * 2.6f), h, 0x99000000, 0x00000000);
		disc(m, cx, cy, r + 5, RIM);
		boolean flash = t >= 1 && (now / 250) % 2 == 0;
		// lit up: the slice at the pointer while spinning, then the result flashing
		int lit = t < 1 ? under : flash ? spin.result() : -1;
		int[] shown = new int[n];
		for (int i = 0; i < n; i++) {
			double a0 = rot + i * slice;
			shown[i] = spin.colors().get(i) | 0xFF000000;
			if (i == lit) {
				shown[i] = brighten(shown[i], t >= 1 ? 70 : 35);
			}
			wedge(m, cx, cy, r, a0, a0 + slice, shown[i]);
		}
		if (n > 1) {
			float line = Math.max(1f, r / 110f);
			for (int i = 0; i < n; i++) {
				spoke(m, cx, cy, r, rot + i * slice, line, 0xAA0B0D10);
			}
		}
		disc(m, cx, cy, r * 0.16f, RIM);
		disc(m, cx, cy, r * 0.12f, 0xFFF2C14E);
		// The pointer: a white arrow at 3 o'clock pointing at the rim
		BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		buf.addVertex(m, cx + r + 18, cy - 11, 0).setColor(RIM);
		buf.addVertex(m, cx + r - 8, cy, 0).setColor(RIM);
		buf.addVertex(m, cx + r + 18, cy + 11, 0).setColor(RIM);
		buf.addVertex(m, cx + r + 15, cy - 8, 0).setColor(0xFFFFFFFF);
		buf.addVertex(m, cx + r - 4, cy, 0).setColor(0xFFFFFFFF);
		buf.addVertex(m, cx + r + 15, cy + 8, 0).setColor(0xFFFFFFFF);
		BufferUploader.drawWithShader(buf.buildOrThrow());
		RenderSystem.disableBlend();

		// Names along their slices (only the half you can see), sized to fit the slice
		float inner = r * 0.2f;
		float outer = r * 0.94f;
		float textScale = Mth.clamp((float) (r * 0.6f * Math.toRadians(slice)) / 10.5f, 0.55f, 1.15f);
		int maxW = (int) ((outer - inner) / textScale);
		for (int i = 0; i < n; i++) {
			double mid = rot + (i + 0.5) * slice;
			double norm = ((mid % 360) + 360) % 360;
			if (norm > 95 && norm < 265) {
				continue; // behind the left edge
			}
			boolean light = luminance(shown[i]) > 150; // the colour as drawn (the lit slice is brighter)
			String label = font.plainSubstrByWidth(spin.names().get(i), maxW);
			if (!label.equals(spin.names().get(i))) {
				label = font.plainSubstrByWidth(spin.names().get(i), maxW - font.width("..")) + "..";
			}
			ctx.pose().pushPose();
			ctx.pose().translate(cx, cy, 0);
			ctx.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) mid));
			ctx.pose().scale(textScale, textScale, 1);
			// right-aligned against the rim, so names line up at the edge where the pointer is
			int x = (int) (outer / textScale) - font.width(label);
			ctx.drawString(font, label, x, -4, light ? 0xFF15181D : 0xFFFFFFFF, !light);
			ctx.pose().popPose();
		}

		// Next to the pointer: what it's on now, then the result
		float tx = cx + r + 26;
		int room = (int) (w - tx - 8);
		if (room > 40) {
			String title = spin.title();
			String current = spin.names().get(t >= 1 ? spin.result() : under);
			int curColor = spin.colors().get(t >= 1 ? spin.result() : under) | 0xFF000000;
			float big = t >= 1 ? 2.2f : 1.6f;
			big = Math.min(big, room / (float) Math.max(1, font.width(current)));
			ctx.drawString(font, Component.literal(font.plainSubstrByWidth(title, room)), (int) tx, (int) (cy - 9 * big / 2 - 14), 0xFFB8C0CC, true);
			ctx.pose().pushPose();
			ctx.pose().translate(tx, cy - 9 * big / 2, 0);
			ctx.pose().scale(big, big, 1);
			ctx.drawString(font, Component.literal(current + (t >= 1 ? "!" : "")), 0, 0, readable(curColor), true);
			ctx.pose().popPose();
			if (t >= 1) {
				ctx.drawString(font, Component.literal("The wheel has spoken"), (int) tx, (int) (cy + 9 * big / 2 + 5), 0xFF8B95A3, true);
			}
		}
	}

	/** Slice colours as text on the dark readout: very dark ones are lifted so they stay readable. */
	private static int readable(int c) {
		return luminance(c) < 90 ? brighten(c, 90) : c;
	}

	private static double luminance(int c) {
		return 0.299 * ((c >> 16) & 255) + 0.587 * ((c >> 8) & 255) + 0.114 * (c & 255);
	}

	private static int brighten(int c, int by) {
		int r = Math.min(255, ((c >> 16) & 255) + by);
		int g = Math.min(255, ((c >> 8) & 255) + by);
		int b = Math.min(255, (c & 255) + by);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static void disc(Matrix4f m, float cx, float cy, float r, int color) {
		wedge(m, cx, cy, r, 0, 360, color);
	}

	private static void wedge(Matrix4f m, float cx, float cy, float r, double fromDeg, double toDeg, int color) {
		int steps = Math.max(2, (int) Math.ceil((toDeg - fromDeg) / 3));
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

	/** A thin line from the hub to the rim between two slices. */
	private static void spoke(Matrix4f m, float cx, float cy, float r, double deg, float width, int color) {
		double a = Math.toRadians(deg);
		float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
		float px = -dy * width / 2, py = dx * width / 2;
		BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		buf.addVertex(m, cx + px, cy + py, 0).setColor(color);
		buf.addVertex(m, cx + dx * r + px, cy + dy * r + py, 0).setColor(color);
		buf.addVertex(m, cx + dx * r - px, cy + dy * r - py, 0).setColor(color);
		buf.addVertex(m, cx - px, cy - py, 0).setColor(color);
		MeshData mesh = buf.build();
		if (mesh != null) {
			BufferUploader.drawWithShader(mesh);
		}
	}

	/** A left-to-right colour fade. */
	private static void hGradient(Matrix4f m, float x0, float y0, float x1, float y1, int from, int to) {
		BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		buf.addVertex(m, x0, y0, 0).setColor(from);
		buf.addVertex(m, x0, y1, 0).setColor(from);
		buf.addVertex(m, x1, y1, 0).setColor(to);
		buf.addVertex(m, x1, y0, 0).setColor(to);
		MeshData mesh = buf.build();
		if (mesh != null) {
			BufferUploader.drawWithShader(mesh);
		}
	}
}
