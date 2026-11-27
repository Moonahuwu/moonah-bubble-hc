package moonahmc.bubble.client;

import moonahmc.bubble.Payloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

/**
 * Bullet cam: when the replay's kill came from far off (a skeleton's arrow, a sniper's bullet, a ghast's fireball), the
 * camera rides just behind the shot from as far as the replay saw it until it hits, then goes back to the normal view.
 * Used by both the down cam and the run-ending kill cam; only this client's camera moves.
 */
final class BulletCam {
	private static final int CAM_ID = -0x5BB21;
	private static ArmorStand cam;
	/** Whatever we were looking through before (the replay's camera), to go back to. */
	private static Entity before;
	private static int from;
	/** Which replay has it ("down" or "kill"). */
	private static String owner;

	private BulletCam() {
	}

	static boolean active() {
		return cam != null;
	}

	/** Each tick of a replay, with its current (fractional) frame. Takes the camera while the shot is in the air. */
	static void update(String who, Payloads.Killcam clip, double idx) {
		if (cam != null && !who.equals(owner)) {
			return; // the other replay has it
		}
		Minecraft mc = Minecraft.getInstance();
		Payloads.Actor shot = clip == null || clip.shot() <= 0 || clip.scene() == null ? null : find(clip);
		double local = shot == null ? -1 : idx - shot.start();
		if (shot == null || mc.level == null || local < 0 || local > shot.frames().size() - 1 || shot.frames().size() < 2) {
			release(who);
			return;
		}
		Payloads.ActorFrame f = Payloads.ActorFrame.lerp(shot.frames(), local);
		Vec3 dir = new Vec3(f.vx(), f.vy(), f.vz());
		if (dir.lengthSqr() < 1e-4) {
			// no velocity recorded: head for the next spot it was at
			int i = Math.min(shot.frames().size() - 1, (int) local + 1);
			Payloads.ActorFrame next = shot.frames().get(i);
			dir = new Vec3(next.x() - f.x(), next.y() - f.y(), next.z() - f.z());
		}
		if (dir.lengthSqr() < 1e-4) {
			return; // stuck in the air for a frame: keep the last view
		}
		dir = dir.normalize();
		// a little behind and above it, looking where it's going (the armour stand's eyes are 1.7 up)
		double x = f.x() - dir.x * 1.6;
		double y = f.y() - dir.y * 1.6 + 0.2 - 1.7;
		double z = f.z() - dir.z * 1.6;
		float yaw = (float) (Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90);
		float pitch = (float) -Math.toDegrees(Math.atan2(dir.y, Math.hypot(dir.x, dir.z)));
		if (cam == null || cam.level() != mc.level) {
			release(who);
			cam = new ArmorStand(EntityType.ARMOR_STAND, mc.level);
			cam.setId(CAM_ID);
			cam.setInvisible(true);
			cam.setNoGravity(true);
			cam.setSilent(true);
			cam.moveTo(x, y, z, yaw, pitch);
			cam.setYHeadRot(yaw);
			mc.level.addEntity(cam);
			ReplayActors.exempt(cam);
			before = mc.getCameraEntity();
			from = clip.shotFrom();
			owner = who;
			mc.setCameraEntity(cam);
		} else {
			cam.setPos(x, y, z);
			cam.setYRot(yaw);
			cam.setXRot(pitch);
			cam.setYHeadRot(yaw);
		}
	}

	/** Back to the replay's own camera (or your eyes). */
	static void release(String who) {
		if (cam == null || (owner != null && !owner.equals(who))) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.getCameraEntity() == cam) {
			Entity back = before != null && !before.isRemoved() && before.level() == mc.level ? before : mc.player;
			if (back != null) {
				mc.setCameraEntity(back);
			}
		}
		if (mc.level != null && cam.level() == mc.level) {
			mc.level.removeEntity(cam.getId(), Entity.RemovalReason.DISCARDED);
		}
		ReplayActors.unexempt(cam);
		cam = null;
		before = null;
		owner = null;
	}

	private static Payloads.Actor find(Payloads.Killcam clip) {
		for (Payloads.Actor a : clip.scene().actors()) {
			if (a.entity() == clip.shot() && (a.kind() == Payloads.Scene.PROJECTILE || a.kind() == Payloads.Scene.TRACER)) {
				return a;
			}
		}
		return null;
	}

	static void renderHud(GuiGraphics ctx) {
		if (cam == null) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		String text = "Shot from " + from + " blocks away";
		int w = ctx.guiWidth();
		int y = ctx.guiHeight() - 60;
		ctx.drawString(font, text, (w - font.width(text)) / 2, y, Rarity.pulse(0xFFFFC94A, 0xFFFFF2B0, 1.4f), true);
	}
}
