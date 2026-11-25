package moonahmc.bubble.client;

import com.mojang.authlib.GameProfile;
import moonahmc.bubble.Payloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Your own down cam (revive): when you go down, your screen plays back the few seconds before it from over your
 * shoulder, slowing down at the hit, then goes back to your own eyes. Only you see it, and only your camera moves (the
 * server isn't involved), so you're still where you fell. Click to skip; off in the Bubble menu ("Replay when you go
 * down").
 */
final class DownCam {
	private static final int GHOST_ID = -0x5BB1F;
	private static final int CAM_ID = -0x5BB20;
	/** How long it stays on you after the hit before going back to your eyes. */
	private static final int HOLD = 24;
	private static final EquipmentSlot[] GEAR_SLOTS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
			EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	private static Payloads.Killcam clip;
	private static KillcamReplay.Ghost ghost;
	private static ArmorStand cam;
	private static int t;
	private static int lastFrame;
	private static double startAngle;
	/** Whose replay this is, and whether it's yours. */
	private static UUID subject;
	private static boolean mine;
	/** Everyone and everything else that was there. */
	private static ReplayActors actors;

	private DownCam() {
	}

	static boolean active() {
		return cam != null;
	}

	/** The player whose knockdown is playing (their real body is hidden meanwhile), or null. */
	static UUID subject() {
		return cam == null ? null : subject;
	}

	static void start(Payloads.Killcam replay) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || replay.frames().size() < 2
				|| !mc.level.dimension().location().toString().equals(replay.dimension())) {
			return;
		}
		boolean isMine = replay.player().equals(mc.player.getUUID());
		if (ClientSettings.downCam == 0 || (ClientSettings.downCam == 1 && !isMine)) {
			return;
		}
		if (active() && mine && !isMine) {
			return; // your own replay is playing: don't cut it off for someone else's
		}
		if (!isMine && (mc.screen != null || mc.player.hurtTime > 0)) {
			return; // busy (in a menu, or being hit): skip theirs
		}
		stop();
		subject = replay.player();
		mine = isMine;
		ClientLevel level = mc.level;
		Payloads.Frame first = replay.frames().get(0);
		ghost = new KillcamReplay.Ghost(level, new GameProfile(UUID.randomUUID(), replay.name()), replay.player());
		ghost.setId(GHOST_ID);
		ghost.moveTo(first.x(), first.y(), first.z(), first.yaw(), first.pitch());
		ghost.setYHeadRot(first.headYaw());
		ghost.setYBodyRot(first.yaw());
		for (int i = 0; i < GEAR_SLOTS.length && i < replay.gear().size(); i++) {
			ItemStack stack = replay.gear().get(i);
			ghost.setItemSlot(GEAR_SLOTS[i], stack);
		}
		level.addEntity(ghost);
		cam = new ArmorStand(EntityType.ARMOR_STAND, level);
		cam.setId(CAM_ID);
		cam.setInvisible(true);
		cam.setNoGravity(true);
		cam.setSilent(true);
		startAngle = Math.toRadians(first.yaw()) - Math.PI / 2;
		clip = replay;
		t = 0;
		lastFrame = 0;
		placeCamera(true);
		level.addEntity(cam);
		ReplayActors.exempt(ghost);
		ReplayActors.exempt(cam);
		actors = new ReplayActors(level, replay.scene());
		actors.show(0);
		mc.setCameraEntity(cam);
	}

	/** Over the shoulder while it plays, then a slow turn round the spot. Same as the run-ending kill cam. */
	private static void placeCamera(boolean jump) {
		List<Payloads.Frame> frames = clip.frames();
		int end = Payloads.Killcam.replayTicks(frames.size());
		Payloads.Frame f = Payloads.Killcam.lerp(frames, Payloads.Killcam.frameAt(Math.min(t, end), frames.size()));
		double a = startAngle + Math.toRadians(t * (t < end ? 0.5 : 1.4));
		double radius = t < end ? 4.5 : 4.5 + Math.min(1.5, (t - end) * 0.05);
		double x = f.x() + Math.cos(a) * radius;
		double z = f.z() + Math.sin(a) * radius;
		double y = f.y() + 0.6;
		double dx = f.x() - x;
		double dz = f.z() - z;
		double dy = (f.y() + 0.9) - (y + cam.getEyeHeight());
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
		if (jump) {
			cam.moveTo(x, y, z, yaw, pitch);
		} else {
			cam.setPos(x, y, z);
			cam.setYRot(yaw);
			cam.setXRot(pitch);
		}
		cam.setYHeadRot(yaw);
	}

	static void tick(Minecraft mc) {
		if (cam == null) {
			return;
		}
		if (mc.level == null || cam.level() != mc.level || mc.player == null) {
			stop();
			return;
		}
		if (!mine && mc.player.hurtTime > 0 && !moonahmc.bubble.Revive.isDown(mc.player)) {
			stop(); // you're being hit while watching someone else's: back to you
			return;
		}
		t++;
		List<Payloads.Frame> frames = clip.frames();
		int end = Payloads.Killcam.replayTicks(frames.size());
		actors.show(Payloads.Killcam.frameAt(Math.min(t, end), frames.size()));
		if (t <= end) {
			double idx = Payloads.Killcam.frameAt(t, frames.size());
			Payloads.Frame f = Payloads.Killcam.lerp(frames, idx);
			ghost.lerpTo(f.x(), f.y(), f.z(), f.yaw(), f.pitch(), 1);
			ghost.lerpHeadTo(f.headYaw(), 1);
			Payloads.Frame raw = frames.get(Math.min(frames.size() - 1, (int) idx));
			ghost.setPose(raw.has(Payloads.Frame.SWIM) ? Pose.SWIMMING
					: raw.has(Payloads.Frame.GLIDE) ? Pose.FALL_FLYING
					: raw.has(Payloads.Frame.CROUCH) ? Pose.CROUCHING : Pose.STANDING);
			ghost.setShiftKeyDown(raw.has(Payloads.Frame.CROUCH));
			for (int i = lastFrame + 1; i <= (int) idx && i < frames.size(); i++) {
				if (frames.get(i).has(Payloads.Frame.SWING)) {
					ghost.swing(InteractionHand.MAIN_HAND);
				}
				if (frames.get(i).has(Payloads.Frame.HURT)) {
					ghost.hurtTime = ghost.hurtDuration = 10;
				}
			}
			lastFrame = Math.max(lastFrame, (int) idx);
		}
		if (t == end) {
			// the hit that put you down: you drop to a crawl
			ghost.hurtTime = ghost.hurtDuration = 10;
			ghost.setPose(Pose.SWIMMING);
			ghost.setForcedPose(Pose.SWIMMING);
			mc.level.playLocalSound(ghost.getX(), ghost.getY(), ghost.getZ(), SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1f, 0.6f, false);
		}
		placeCamera(false);
		if (t >= end + HOLD) {
			stop();
		}
	}

	static void stop() {
		Minecraft mc = Minecraft.getInstance();
		if (cam != null && mc.getCameraEntity() == cam && mc.player != null) {
			mc.setCameraEntity(mc.player);
		}
		if (mc.level != null) {
			for (Entity e : new Entity[] {ghost, cam}) {
				if (e != null && e.level() == mc.level) {
					mc.level.removeEntity(e.getId(), Entity.RemovalReason.DISCARDED);
				}
			}
		}
		if (actors != null) {
			actors.stop();
		}
		ReplayActors.unexempt(ghost);
		ReplayActors.unexempt(cam);
		actors = null;
		ghost = null;
		cam = null;
		clip = null;
		subject = null;
		mine = false;
	}

	static void renderHud(GuiGraphics ctx) {
		if (cam == null) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		int w = ctx.guiWidth();
		ctx.fill(0, 0, w, 22, 0x88000000);
		String title = mine || clip == null ? "How you went down" : "How " + clip.name() + " went down";
		ctx.drawString(font, title, (w - font.width(title)) / 2, 4, Rarity.pulse(0xFFFF5555, 0xFFFFC0C0, 1.4f), true);
		String skip = "Click to skip";
		ctx.drawString(font, skip, (w - font.width(skip)) / 2, 13, 0xFF9AA3AF, false);
	}
}
