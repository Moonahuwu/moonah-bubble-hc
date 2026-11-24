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
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ribbon trails (Eidolon cosmetics, GMod style): a strip of texture that follows a player along the ground and fades
 * out behind them. Everyone draws everyone's, from the list the server sends (Eidolons.Ribbons). The strip turns to
 * face the camera, sits on the ground, narrows and fades towards the tail, and its texture stays where it was laid
 * down (so "LOL" stays put while you walk away from it).
 */
final class RibbonTrails {
	/** How long a bit of ribbon lasts, in ticks. */
	private static final int LIFE = 30;
	/** How wide the ribbon is at your feet, in blocks. */
	private static final float WIDTH = 0.6f;
	/** One copy of the texture along the ribbon, in blocks (the textures are 4:1). */
	private static final double TILE = WIDTH * 4;
	private static final int MAX_POINTS = 80;

	private record Point(Vec3 pos, long tick, double dist) {
	}

	private static final class Trail {
		final ArrayDeque<Point> points = new ArrayDeque<>();
		String id;
	}

	static Map<UUID, String> worn = Map.of();
	private static final Map<UUID, Trail> trails = new HashMap<>();
	private static final Map<String, ResourceLocation> textures = new HashMap<>();

	private RibbonTrails() {
	}

	static void setWorn(Map<UUID, String> map) {
		worn = Map.copyOf(map);
	}

	static void reset() {
		worn = Map.of();
		trails.clear();
	}

	private static ResourceLocation texture(String id) {
		return textures.computeIfAbsent(id, k -> ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "textures/trail/" + k + ".png"));
	}

	private static boolean shows(Player p) {
		return worn.containsKey(p.getUUID()) && !p.isSpectator() && !p.isInvisible() && p.isAlive();
	}

	/** Every client tick: drop a new point where each player is (if they moved), and forget old ones. */
	static void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			trails.clear();
			return;
		}
		long now = mc.level.getGameTime();
		List<UUID> seen = new ArrayList<>();
		for (Player p : mc.level.players()) {
			if (!shows(p)) {
				continue;
			}
			UUID id = p.getUUID();
			seen.add(id);
			Trail t = trails.computeIfAbsent(id, k -> new Trail());
			String want = worn.get(id);
			if (!want.equals(t.id)) {
				t.id = want;
				t.points.clear();
			}
			Vec3 at = p.position();
			Point last = t.points.peekLast();
			if (last == null) {
				t.points.add(new Point(at, now, 0));
			} else {
				double d = last.pos.distanceTo(at);
				if (d > 6) { // teleported: start again
					t.points.clear();
					t.points.add(new Point(at, now, 0));
				} else if (d > 0.15) {
					t.points.add(new Point(at, now, last.dist + d));
				}
			}
			while (t.points.size() > MAX_POINTS) {
				t.points.pollFirst();
			}
			while (t.points.size() > 1 && now - t.points.peekFirst().tick > LIFE) {
				t.points.pollFirst();
			}
		}
		trails.keySet().retainAll(seen);
	}

	static void render(PoseStack pose, Camera camera, float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (trails.isEmpty() || mc.level == null) {
			return;
		}
		Vec3 cam = camera.getPosition();
		Matrix4f m = pose.last().pose();
		float now = mc.level.getGameTime() + partial;
		RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(false);
		RenderSystem.disableCull();
		for (Map.Entry<UUID, Trail> e : trails.entrySet()) {
			Player p = mc.level.getPlayerByUUID(e.getKey());
			Trail t = e.getValue();
			if (p == null || t.points.isEmpty() || !shows(p)) {
				continue;
			}
			// the points, oldest first, then where the player is right now
			List<Point> pts = new ArrayList<>(t.points);
			Point last = pts.get(pts.size() - 1);
			Vec3 head = p.getPosition(partial);
			double hd = last.pos.distanceTo(head);
			if (hd > 0.01 && hd < 6) {
				pts.add(new Point(head, (long) now, last.dist + hd));
			}
			if (pts.size() < 2) {
				continue;
			}
			RenderSystem.setShaderTexture(0, texture(t.id));
			BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_TEX_COLOR);
			Point prev = null;
			int i = 0;
			for (Point pt : pts) {
				// along: towards the next point (or from the previous one at the end)
				Point next = i + 1 < pts.size() ? pts.get(i + 1) : null;
				Vec3 along = next != null ? next.pos.subtract(pt.pos) : pt.pos.subtract(prev.pos);
				float f = Math.max(0, Math.min(1, 1 - (now - pt.tick) / LIFE));
				float half = WIDTH / 2 * (0.35f + 0.65f * f);
				// sits on the ground at the head; turns to face the camera
				Vec3 centre = pt.pos.add(0, half, 0);
				Vec3 toCam = cam.subtract(centre);
				Vec3 side = along.cross(toCam);
				if (side.lengthSqr() < 1e-8) {
					side = new Vec3(0, 1, 0);
				}
				side = side.normalize().scale(half);
				if (side.y < 0) {
					side = side.scale(-1); // keep the texture the right way up
				}
				float u = (float) (pt.dist / TILE);
				float a = f * f;
				Vec3 lo = centre.subtract(side).subtract(cam);
				Vec3 hi = centre.add(side).subtract(cam);
				buf.addVertex(m, (float) lo.x, (float) lo.y, (float) lo.z).setUv(u, 1).setColor(1f, 1f, 1f, a);
				buf.addVertex(m, (float) hi.x, (float) hi.y, (float) hi.z).setUv(u, 0).setColor(1f, 1f, 1f, a);
				prev = pt;
				i++;
			}
			MeshData mesh = buf.build();
			if (mesh != null) {
				BufferUploader.drawWithShader(mesh);
			}
		}
		RenderSystem.enableCull();
		RenderSystem.depthMask(true);
		RenderSystem.disableBlend();
	}
}
