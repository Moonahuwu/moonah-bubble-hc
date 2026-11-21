package moonahmc.bubble.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Draws the bubble's edge as a glowing forcefield: a wall (ring mode) or a dome (sphere mode). Only the patch near the
 * camera is drawn and it fades out with distance, so it costs about the same as a handful of particles at any size.
 * The grid sits at fixed angles, so the pattern stays put in the world while you move along it.
 */
final class ForcefieldRenderer {
	/** How far along the surface the visible patch reaches (grows with the wall distance setting, up to 80). */
	private static double reach = 30;
	private static final int GRID = 2;
	private static final double LINE = 0.06;

	private static float[] alpha = new float[0];
	private static double[] px = new double[0];
	private static double[] py = new double[0];
	private static double[] pz = new double[0];
	private static double[] wy = new double[0];
	/** Unit vectors along the surface: u = around, v = up. Used to give the grid lines their width. */
	private static double[] ux = new double[0];
	private static double[] uz = new double[0];
	private static double[] vx = new double[0];
	private static double[] vy = new double[0];
	private static double[] vz = new double[0];

	private ForcefieldRenderer() {
	}

	private static void ensure(int n) {
		if (alpha.length < n) {
			alpha = new float[n];
			px = new double[n];
			py = new double[n];
			pz = new double[n];
			wy = new double[n];
			ux = new double[n];
			uz = new double[n];
			vx = new double[n];
			vy = new double[n];
			vz = new double[n];
		}
	}

	static void render(PoseStack pose, Camera camera, float td) {
		Minecraft mc = Minecraft.getInstance();
		if (!ClientState.fieldActive || mc.player == null || mc.level == null) {
			return;
		}
		double cx = Mth.lerp(td, ClientState.prevX, ClientState.fieldX);
		double cy = Mth.lerp(td, ClientState.prevY, ClientState.fieldY);
		double cz = Mth.lerp(td, ClientState.prevZ, ClientState.fieldZ);
		double r = Math.max(1, Mth.lerp(td, ClientState.prevR, ClientState.fieldR));
		boolean sphere = ClientState.fieldSphere;
		Vec3 cam = camera.getPosition();

		double dx = cam.x - cx;
		double dy = cam.y - (cy + 1); // the server measures from feet; the camera is at eye height
		double dz = cam.z - cz;
		double flat = Math.sqrt(dx * dx + dz * dz);
		double dist = sphere ? Math.sqrt(flat * flat + dy * dy) : flat;
		double toEdge = r - dist; // negative = outside
		long now = Util.getMillis();
		float pulseAge = (now - ClientState.pulseAt) / 900f;
		float pulse = pulseAge < 1 ? 1 - pulseAge : 0;
		// How close you have to be before it shows: your own setting in the Bubble menu (or always).
		boolean always = ClientSettings.wallAlways();
		int show = ClientSettings.wallDistance;
		reach = always ? Mth.clamp(toEdge + 30, 30, 80) : Mth.clamp(show + 6, 30, 80);
		float near = always ? 1 : (float) Mth.clamp(1 - toEdge / show, 0, 1);
		near *= near;
		if ((near < 0.01f && pulse <= 0) || dist < 1e-3) {
			return;
		}
		float outside = (float) Mth.clamp(-toEdge / 6, 0, 1);
		float t = (now % 600_000L) / 1000f;

		double step = Math.min(1.0 / r, Math.PI * 2 / 48); // about a block between columns
		double facing = Math.atan2(dz, dx);
		long firstCol;
		int cols;
		long firstRow;
		int rows;
		double rowStep;
		double baseY = 0;
		if (sphere) {
			double el0 = Math.asin(Mth.clamp(dy / dist, -1, 1));
			double spanEl = Math.min(Math.PI / 2, reach / r);
			rowStep = step;
			firstRow = (long) Math.floor(Math.max(-Math.PI / 2, el0 - spanEl) / rowStep);
			rows = (int) Mth.clamp(Math.ceil(Math.min(Math.PI / 2, el0 + spanEl) / rowStep) - firstRow, 4, 220);
			double spanAz = Math.min(Math.PI, reach / (r * Math.max(0.2, Math.cos(el0))));
			firstCol = (long) Math.floor((facing - spanAz) / step);
			cols = (int) Mth.clamp(Math.ceil((facing + spanAz) / step) - firstCol, 12, 400);
		} else {
			double span = Math.min(Math.PI, reach / r);
			firstCol = (long) Math.floor((facing - span) / step);
			cols = (int) Mth.clamp(Math.ceil((facing + span) / step) - firstCol, 12, 400);
			rowStep = 1;
			baseY = Math.floor(cam.y - reach * 0.7);
			firstRow = 0;
			rows = (int) (Math.ceil(cam.y + reach) - baseY);
		}
		int stride = cols + 1;
		ensure(stride * (rows + 1));

		// Node positions (relative to the camera) and brightness.
		for (int row = 0; row <= rows; row++) {
			long rowIndex = firstRow + row;
			double el = sphere ? Mth.clamp(rowIndex * rowStep, -Math.PI / 2, Math.PI / 2) : 0;
			double cosEl = Math.cos(el);
			double sinEl = Math.sin(el);
			double worldRowY = sphere ? cy + sinEl * r : baseY + row;
			double vCoord = sphere ? el * r : worldRowY; // distance "up" the surface, for the shimmer
			float scan = (float) Math.pow(Math.max(0, Math.sin(worldRowY * 0.45 - t * 2.4)), 14);
			for (int c = 0; c <= cols; c++) {
				int i = row * stride + c;
				double a = (firstCol + c) * step;
				double cosA = Math.cos(a);
				double sinA = Math.sin(a);
				double rr = sphere ? r * cosEl : r;
				px[i] = cx + cosA * rr - cam.x;
				pz[i] = cz + sinA * rr - cam.z;
				py[i] = worldRowY - cam.y;
				wy[i] = worldRowY;
				ux[i] = -sinA;
				uz[i] = cosA;
				if (sphere) {
					vx[i] = -sinEl * cosA;
					vy[i] = cosEl;
					vz[i] = -sinEl * sinA;
				} else {
					vx[i] = 0;
					vy[i] = 1;
					vz[i] = 0;
				}
				double d = Math.sqrt(px[i] * px[i] + py[i] * py[i] + pz[i] * pz[i]);
				float fade = (float) Mth.clamp(1 - d / reach, 0, 1);
				fade *= fade;
				double s = a * r;
				float shimmer = 0.55f + 0.45f * (float) (Math.sin(t * 1.7 + vCoord * 0.8 + s * 0.33) * Math.sin(t * 1.1 - s * 0.5 + vCoord * 0.27));
				float ripple = 0;
				if (pulse > 0) {
					double ring = d - pulseAge * 18;
					ripple = (float) Math.exp(-ring * ring / 3) * pulse;
				}
				alpha[i] = fade * (near * (0.20f * shimmer + 0.30f * scan) + 0.65f * ripple + 0.12f * pulse);
			}
		}

		PoseStack stack = pose != null ? pose : new PoseStack();
		Matrix4f m = stack.last().pose();
		BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

		// The glowing surface.
		for (int row = 0; row < rows; row++) {
			for (int c = 0; c < cols; c++) {
				int i00 = row * stride + c;
				int i01 = i00 + 1;
				int i10 = i00 + stride;
				int i11 = i10 + 1;
				if (alpha[i00] + alpha[i01] + alpha[i10] + alpha[i11] < 0.004f) {
					continue;
				}
				vertex(buf, m, i00, 0, 0, 0, 1, pulse, outside);
				vertex(buf, m, i01, 0, 0, 0, 1, pulse, outside);
				vertex(buf, m, i11, 0, 0, 0, 1, pulse, outside);
				vertex(buf, m, i10, 0, 0, 0, 1, pulse, outside);
			}
		}

		// Brighter grid lines every couple of blocks, so it reads as a forcefield instead of fog.
		for (int c = 0; c <= cols; c++) {
			if (Math.floorMod(firstCol + c, GRID) != 0) {
				continue;
			}
			for (int row = 0; row < rows; row++) {
				int i0 = row * stride + c;
				int i1 = i0 + stride;
				if (alpha[i0] + alpha[i1] < 0.003f) {
					continue;
				}
				vertex(buf, m, i0, -ux[i0] * LINE, 0, -uz[i0] * LINE, 1.8f, pulse, outside);
				vertex(buf, m, i0, ux[i0] * LINE, 0, uz[i0] * LINE, 1.8f, pulse, outside);
				vertex(buf, m, i1, ux[i1] * LINE, 0, uz[i1] * LINE, 1.8f, pulse, outside);
				vertex(buf, m, i1, -ux[i1] * LINE, 0, -uz[i1] * LINE, 1.8f, pulse, outside);
			}
		}
		for (int row = 0; row <= rows; row++) {
			long rowIndex = sphere ? firstRow + row : (long) (baseY + row);
			if (Math.floorMod(rowIndex, GRID) != 0) {
				continue;
			}
			for (int c = 0; c < cols; c++) {
				int i0 = row * stride + c;
				int i1 = i0 + 1;
				if (alpha[i0] + alpha[i1] < 0.003f) {
					continue;
				}
				vertex(buf, m, i0, -vx[i0] * LINE, -vy[i0] * LINE, -vz[i0] * LINE, 1.8f, pulse, outside);
				vertex(buf, m, i1, -vx[i1] * LINE, -vy[i1] * LINE, -vz[i1] * LINE, 1.8f, pulse, outside);
				vertex(buf, m, i1, vx[i1] * LINE, vy[i1] * LINE, vz[i1] * LINE, 1.8f, pulse, outside);
				vertex(buf, m, i0, vx[i0] * LINE, vy[i0] * LINE, vz[i0] * LINE, 1.8f, pulse, outside);
			}
		}

		MeshData built = buf.build();
		if (built == null) {
			return;
		}
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		RenderSystem.enableBlend();
		RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE); // additive glow
		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(false);
		RenderSystem.disableCull();
		BufferUploader.drawWithShader(built);
		RenderSystem.enableCull();
		RenderSystem.depthMask(true);
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableBlend();
	}

	/** Mint at the bottom fading to sky blue at the top; whiter when you bump it, redder when you're outside. */
	private static void vertex(BufferBuilder buf, Matrix4f m, int i, double ox, double oy, double oz, float boost, float pulse, float outside) {
		float h = (float) (0.5 + 0.5 * Math.sin(wy[i] * 0.06));
		float r = Mth.lerp(h, 0.42f, 0.38f);
		float g = Mth.lerp(h, 0.90f, 0.70f);
		float b = Mth.lerp(h, 0.72f, 1.00f);
		r = Mth.lerp(outside, r, 1.0f);
		g = Mth.lerp(outside, g, 0.45f);
		b = Mth.lerp(outside, b, 0.45f);
		r = Mth.lerp(pulse * 0.6f, r, 1f);
		g = Mth.lerp(pulse * 0.6f, g, 1f);
		b = Mth.lerp(pulse * 0.6f, b, 1f);
		buf.addVertex(m, (float) (px[i] + ox), (float) (py[i] + oy), (float) (pz[i] + oz)).setColor(r, g, b, Math.min(1f, alpha[i] * boost));
	}
}
