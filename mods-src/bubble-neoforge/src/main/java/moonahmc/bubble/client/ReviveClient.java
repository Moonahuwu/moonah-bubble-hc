package moonahmc.bubble.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import moonahmc.bubble.Bubble;
import moonahmc.bubble.Revive;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Revive, as players see it: the REVIVE sign over everyone who's down (through walls, yellow going red as their time
 * runs out, with a green bar while someone revives them), and a message for whoever is down or reviving.
 */
final class ReviveClient {
	private static final ResourceLocation ICON = ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "textures/misc/revive.png");
	static List<Revive.Entry> downed = List.of();
	static Revive.Settings settings;

	private ReviveClient() {
	}

	static void reset() {
		setDowned(List.of());
		settings = null;
	}

	static void setDowned(List<Revive.Entry> list) {
		downed = list;
		Revive.clientDownIds = list.stream().map(Revive.Entry::entityId).collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	/** Am I down right now? */
	static boolean meDown() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && Revive.clientDownIds.contains(mc.player.getId());
	}

	private static boolean forcedPose;

	/**
	 * While you're down your own game keeps you crawling (slow, like crawling under a trapdoor), with no sprinting,
	 * jumping or swimming up, and the attack and use keys do nothing. The server blocks all of this too.
	 */
	static void registerControls(net.neoforged.bus.api.IEventBus bus) {
		bus.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) -> {
			var p = Minecraft.getInstance().player;
			if (p == null) {
				forcedPose = false;
				return;
			}
			if (meDown()) {
				p.setSprinting(false);
				p.setForcedPose(net.minecraft.world.entity.Pose.SWIMMING);
				forcedPose = true;
			} else if (forcedPose) {
				p.setForcedPose(null);
				forcedPose = false;
			}
		});
		bus.addListener((net.neoforged.neoforge.client.event.MovementInputUpdateEvent e) -> {
			if (meDown()) {
				e.getInput().jumping = false;
			}
		});
		bus.addListener((net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered e) -> {
			if (meDown() || DownCam.active()) {
				e.setCanceled(true);
				e.setSwingHand(false);
				if (DownCam.active()) {
					DownCam.stop(); // click to skip your down cam
				}
			}
		});
	}

	private static String myName() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? "" : mc.player.getGameProfile().getName();
	}

	/** 1 = full time left, 0 = about to bleed out. */
	private static float timeLeft(Revive.Entry e) {
		return Math.max(0, Math.min(1, e.left() / (float) Math.max(1, e.total())));
	}

	/** The sign's tint: the yellow goes red as time runs out (the black parts stay black). */
	private static float greenBlue(Revive.Entry e) {
		return 0.12f + 0.88f * timeLeft(e);
	}

	// ------------------------------------------------------------------ in the world

	static void renderWorld(PoseStack pose, Camera camera, float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (downed.isEmpty() || mc.level == null) {
			return;
		}
		Vec3 cam = camera.getPosition();
		Font font = mc.font;
		MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
		for (Revive.Entry e : downed) {
			Entity who = mc.level.getEntity(e.entityId());
			if (who == null || (who == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson()) || (who == mc.player && DownCam.active())) {
				continue;
			}
			Vec3 at = who.getPosition(partial).add(0, who.getBbHeight() + 0.55, 0);
			double dist = at.distanceTo(cam);
			// keeps about the same size on screen far away, so you can find people
			float s = 0.025f * (float) Math.max(1, dist / 10);
			pose.pushPose();
			pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
			pose.mulPose(camera.rotation());
			pose.scale(s, -s, s);
			Matrix4f m = pose.last().pose();

			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.disableDepthTest();
			RenderSystem.disableCull();
			// the sign (bobbing a little, pulsing faster as time runs out)
			float pulse = timeLeft(e) < 0.3f ? 1 + 0.06f * (float) Math.sin(net.minecraft.Util.getMillis() / (60.0 + 200 * timeLeft(e))) : 1;
			float half = 18 * pulse;
			float bottom = -4;
			float top = bottom - 2 * half;
			float gb = greenBlue(e);
			RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
			RenderSystem.setShaderTexture(0, ICON);
			BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
			buf.addVertex(m, -half, top, 0).setUv(0, 0).setColor(1f, gb, gb, 1f);
			buf.addVertex(m, -half, bottom, 0).setUv(0, 1).setColor(1f, gb, gb, 1f);
			buf.addVertex(m, half, bottom, 0).setUv(1, 1).setColor(1f, gb, gb, 1f);
			buf.addVertex(m, half, top, 0).setUv(1, 0).setColor(1f, gb, gb, 1f);
			BufferUploader.drawWithShader(buf.buildOrThrow());
			// revive progress under the sign
			if (e.progress() > 0) {
				RenderSystem.setShader(GameRenderer::getPositionColorShader);
				rect(m, -20, 1, 20, 5, 0xCC0B0D10);
				rect(m, -19, 2, -19 + 38 * e.progress(), 4, 0xFF6EE08A);
			}
			RenderSystem.enableCull();
			RenderSystem.enableDepthTest();
			RenderSystem.disableBlend();
			// seconds left
			String secs = e.progress() > 0 ? "Reviving..." : (int) Math.ceil(e.left() / 20.0) + "s";
			int color = e.progress() > 0 ? 0xFF6EE08A : 0xFF000000 | (255 << 16) | ((int) (gb * 220) << 8) | (int) (gb * 60);
			font.drawInBatch(secs, -font.width(secs) / 2f, e.progress() > 0 ? 8 : 2, color, false, m, buffers, Font.DisplayMode.SEE_THROUGH,
					0x66000000, LightTexture.FULL_BRIGHT);
			buffers.endBatch();
			pose.popPose();
		}
	}

	private static void rect(Matrix4f m, float x0, float y0, float x1, float y1, int color) {
		BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		buf.addVertex(m, x0, y0, 0).setColor(color);
		buf.addVertex(m, x0, y1, 0).setColor(color);
		buf.addVertex(m, x1, y1, 0).setColor(color);
		buf.addVertex(m, x1, y0, 0).setColor(color);
		MeshData mesh = buf.build();
		if (mesh != null) {
			BufferUploader.drawWithShader(mesh);
		}
	}

	// ------------------------------------------------------------------ on screen

	static void renderHud(GuiGraphics ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (downed.isEmpty() || mc.player == null || mc.options.hideGui) {
			return;
		}
		String me = myName();
		int w = ctx.guiWidth();
		int h = ctx.guiHeight();
		Font font = mc.font;
		for (Revive.Entry e : downed) {
			if (e.name().equals(me)) {
				// You're down: red edges, how long you've got, what to do
				float t = timeLeft(e);
				int edge = (int) (0x50 + 0x60 * (1 - t)) << 24 | 0x8B0000;
				ctx.fillGradient(0, 0, w, h / 4, edge, 0x008B0000);
				ctx.fillGradient(0, h - h / 4, w, h, 0x008B0000, edge);
				int y = (int) (h * 0.30f);
				ctx.pose().pushPose();
				ctx.pose().translate(w / 2f, y, 0);
				ctx.pose().scale(2.2f, 2.2f, 1);
				String title = "YOU'RE DOWN";
				ctx.drawString(font, title, -font.width(title) / 2, 0, 0xFFFF5555, true);
				ctx.pose().popPose();
				String line = e.progress() > 0 ? e.helper() + " is reviving you" : "Bleeding out in " + (int) Math.ceil(e.left() / 20.0) + "s";
				ctx.drawString(font, line, (w - font.width(line)) / 2, y + 26, e.progress() > 0 ? 0xFF6EE08A : 0xFFFFFFFF, true);
				String help = "A teammate has to stand right next to you";
				if (e.progress() <= 0) {
					ctx.drawString(font, help, (w - font.width(help)) / 2, y + 38, 0xFFB8C0CC, true);
				}
				bar(ctx, w / 2 - 60, y + 52, 120, e.progress() > 0 ? e.progress() : t, e.progress() > 0 ? 0xFF6EE08A : 0xFFFF5555);
				return;
			}
		}
		for (Revive.Entry e : downed) {
			if (e.helper().equals(me) && e.progress() > 0) {
				// You're reviving someone
				int y = (int) (h * 0.62f);
				String line = "Reviving " + e.name();
				ctx.drawString(font, line, (w - font.width(line)) / 2, y, 0xFF6EE08A, true);
				bar(ctx, w / 2 - 50, y + 12, 100, e.progress(), 0xFF6EE08A);
				return;
			}
		}
	}

	private static void bar(GuiGraphics ctx, int x, int y, int width, float f, int color) {
		BubbleScreen.roundRect(ctx, x, y, width, 5, 2, 0xAA0B0D10);
		BubbleScreen.roundRect(ctx, x, y, Math.max(2, Math.round(width * Math.max(0, Math.min(1, f)))), 5, 2, color);
	}

	/** For the Bubble menu's Revive card. */
	static void sendEdit(boolean enabled, int seconds, int hold) {
		moonahmc.bubble.client.net.ClientPlayNetworking.send(new Revive.Edit(enabled, seconds, hold));
	}

	static Component describe() {
		Revive.Settings s = settings;
		return Component.literal(s == null ? "" : !s.enabled() ? "Off: dying is dying"
				: s.seconds() + "s to get to them · " + (s.holdSeconds() == 0 ? "instant" : s.holdSeconds() + "s") + " standing next to them");
	}
}
