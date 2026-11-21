package moonahmc.bubble.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

/**
 * The video someone paid to show everyone (shop Video tab), in a window at the top left of the screen. F7 hides it
 * (and mutes it) just for you. It closes by itself when the video ends, fails, or after 15 minutes.
 */
final class VideoOverlay {
	/** What WaterMediaVideo does; kept as an interface so this class loads without WaterMedia. */
	interface Backend {
		void tick(boolean muted, int pixelW, int pixelH);

		boolean failed();

		boolean ended();

		int texture();

		int width();

		int height();

		void release();
	}

	private static final long MAX_MS = 15 * 60_000;
	private static Backend video;
	private static String by = "";
	private static long startedAt;
	private static long failedAt;
	private static boolean hidden;

	private VideoOverlay() {
	}

	static boolean available() {
		return net.neoforged.fml.ModList.get().isLoaded("watermedia");
	}

	static boolean playing() {
		return video != null;
	}

	static String by() {
		return by;
	}

	static boolean hidden() {
		return hidden;
	}

	static void start(moonahmc.bubble.VideoShare.Start s) {
		stop();
		if (s.url().isEmpty() || !available()) {
			return;
		}
		try {
			video = new WaterMediaVideo(s.url(), s.elapsedMs());
		} catch (Throwable t) {
			moonahmc.bubble.Bubble.LOG.warn("Video: couldn't start {}", s.url(), t);
			video = null;
			return;
		}
		by = s.by();
		startedAt = System.currentTimeMillis() - s.elapsedMs();
		failedAt = 0;
	}

	static void stop() {
		if (video != null) {
			video.release();
		}
		video = null;
	}

	static void toggleHidden() {
		hidden = !hidden;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.displayClientMessage(Component.literal(hidden ? "Video hidden (F7 to show it again)" : "Video shown"), true);
		}
	}

	private static int boxW(GuiGraphics ctx) {
		return Math.min(260, Math.round(ctx.guiWidth() * 0.36f));
	}

	static void render(GuiGraphics ctx) {
		if (video == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		int w = boxW(ctx);
		double scale = mc.getWindow().getGuiScale();
		video.tick(hidden, (int) Math.ceil(w * scale), (int) Math.ceil(w * 9 / 16.0 * scale));
		long now = System.currentTimeMillis();
		if (video.ended() || now - startedAt > MAX_MS) {
			stop();
			return;
		}
		if (video.failed()) {
			if (failedAt == 0) {
				failedAt = now;
			} else if (now - failedAt > 6000) {
				stop();
				return;
			}
		}
		if (hidden || mc.options.hideGui) {
			return;
		}
		int vw = video.width();
		int vh = video.height();
		int h = vw > 0 && vh > 0 ? Math.round(w * vh / (float) vw) : Math.round(w * 9 / 16f);
		h = Math.min(h, Math.round(ctx.guiHeight() * 0.5f));
		int x = 6;
		int y = 6;
		BubbleScreen.roundRect(ctx, x - 2, y - 2, w + 4, h + 16, 4, 0xC0101418);
		int tex = video.texture();
		if (tex > 0) {
			RenderSystem.setShaderTexture(0, tex);
			RenderSystem.setShader(GameRenderer::getPositionTexShader);
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			Matrix4f m = ctx.pose().last().pose();
			BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
			b.addVertex(m, x, y, 0).setUv(0, 0);
			b.addVertex(m, x, y + h, 0).setUv(0, 1);
			b.addVertex(m, x + w, y + h, 0).setUv(1, 1);
			b.addVertex(m, x + w, y, 0).setUv(1, 0);
			BufferUploader.drawWithShader(b.buildOrThrow());
		} else {
			String msg = video.failed() ? "Couldn't play that video" : "Loading video...";
			ctx.drawString(mc.font, msg, x + (w - mc.font.width(msg)) / 2, y + h / 2 - 4, video.failed() ? 0xFFEF7D7D : 0xFF9AA3AD, false);
		}
		String label = by + "'s video  ·  F7 hides it";
		ctx.drawString(mc.font, mc.font.plainSubstrByWidth(label, w), x, y + h + 3, 0xFF9AA3AD, false);
	}
}
