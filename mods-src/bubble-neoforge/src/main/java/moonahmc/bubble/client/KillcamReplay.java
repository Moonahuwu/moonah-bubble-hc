package moonahmc.bubble.client;

import com.mojang.authlib.GameProfile;
import moonahmc.bubble.Payloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.UUID;

/**
 * Plays back the last few seconds before a death with a stand-in of the player (their skin and gear), slowing down
 * for the final second and ending with the death animation. The server moves everyone's camera around it.
 */
final class KillcamReplay {
	private static final int GHOST_ID = -0x5BB1E;
	private static final EquipmentSlot[] GEAR_SLOTS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
			EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	/** Sable Ragdolls: deaths end in a real ragdoll, not the stand-in falling over. */
	static final boolean RAGDOLLS = net.neoforged.fml.ModList.get().isLoaded("sable_player_ragdoll");
	private static Payloads.Killcam pending;
	private static int pendingAge;
	private static Payloads.Killcam active;
	private static Ghost ghost;
	private static int t;
	private static int lastFrame;
	private static ReplayActors actors;

	private KillcamReplay() {
	}

	static void start(Payloads.Killcam replay) {
		stop();
		if (replay.frames().size() < 2) {
			return;
		}
		pending = replay;
		pendingAge = 0;
	}

	static void tick(Minecraft mc) {
		if (pending != null) {
			// The kill cam may move us to another dimension first; wait until we're in the right world.
			if (mc.level != null && mc.level.dimension().location().toString().equals(pending.dimension())) {
				spawn(mc.level, pending);
				pending = null;
			} else if (++pendingAge > 100) {
				pending = null;
			}
		}
		if (ghost == null) {
			return;
		}
		if (mc.level == null || ghost.level() != mc.level) {
			stop();
			return;
		}
		t++;
		List<Payloads.Frame> frames = active.frames();
		int end = Payloads.Killcam.replayTicks(frames.size());
		if (actors != null) {
			actors.show(Payloads.Killcam.frameAt(Math.min(t, end), frames.size()));
		}
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
			// Swings and hits for every frame we stepped over since last tick.
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
		BulletCam.update("kill", active, Payloads.Killcam.frameAt(Math.min(t, end), frames.size()));
		if (t == end && RAGDOLLS) {
			// the server drops their ragdoll right here now: the stand-in just goes (the rest plays on)
			mc.level.playLocalSound(ghost.getX(), ghost.getY(), ghost.getZ(), SoundEvents.PLAYER_DEATH, SoundSource.PLAYERS, 1f, 0.9f, false);
			mc.level.removeEntity(ghost.getId(), Entity.RemovalReason.DISCARDED);
		} else if (t == end) {
			ghost.setHealth(0);
			ghost.hurtTime = ghost.hurtDuration = 10;
			mc.level.playLocalSound(ghost.getX(), ghost.getY(), ghost.getZ(), SoundEvents.PLAYER_DEATH, SoundSource.PLAYERS, 1f, 0.9f, false);
		}
		if (t >= end + 22) {
			for (int i = 0; i < (RAGDOLLS ? 0 : 20); i++) {
				double dx = ghost.getRandom().nextGaussian() * 0.02;
				double dy = ghost.getRandom().nextGaussian() * 0.02;
				double dz = ghost.getRandom().nextGaussian() * 0.02;
				mc.level.addParticle(ParticleTypes.POOF, ghost.getRandomX(1), ghost.getRandomY(), ghost.getRandomZ(1), dx, dy, dz);
			}
			stop();
		}
	}

	private static void spawn(ClientLevel world, Payloads.Killcam replay) {
		Payloads.Frame first = replay.frames().get(0);
		ghost = new Ghost(world, new GameProfile(UUID.randomUUID(), replay.name()), replay.player());
		ghost.setId(GHOST_ID);
		ghost.moveTo(first.x(), first.y(), first.z(), first.yaw(), first.pitch());
		ghost.setYHeadRot(first.headYaw());
		ghost.setYBodyRot(first.yaw());
		for (int i = 0; i < GEAR_SLOTS.length && i < replay.gear().size(); i++) {
			ItemStack stack = replay.gear().get(i);
			ghost.setItemSlot(GEAR_SLOTS[i], stack);
		}
		world.addEntity(ghost);
		ReplayActors.exempt(ghost);
		actors = new ReplayActors(world, replay.scene());
		actors.show(0);
		active = replay;
		t = 0;
		lastFrame = 0;
	}

	static void stop() {
		BulletCam.release("kill");
		pending = null;
		Minecraft mc = Minecraft.getInstance();
		if (ghost != null && mc.level != null && ghost.level() == mc.level) {
			mc.level.removeEntity(ghost.getId(), Entity.RemovalReason.DISCARDED);
		}
		if (actors != null) {
			actors.stop();
			actors = null;
		}
		ReplayActors.unexempt(ghost);
		ghost = null;
		active = null;
	}

	/** A client-only copy of a player that wears the real player's skin. */
	static final class Ghost extends RemotePlayer {
		private final UUID skinOf;

		Ghost(ClientLevel world, GameProfile profile, UUID skinOf) {
			super(world, profile);
			this.skinOf = skinOf;
			getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7F); // hat, jacket, sleeves...
		}

		@Override
		public PlayerSkin getSkin() {
			var handler = Minecraft.getInstance().getConnection();
			PlayerInfo entry = handler == null ? null : handler.getPlayerInfo(skinOf);
			return entry != null ? entry.getSkin() : DefaultPlayerSkin.get(skinOf);
		}
	}
}
