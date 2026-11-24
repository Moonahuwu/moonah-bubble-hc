package moonahmc.bubble.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import moonahmc.bubble.Payloads;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The rest of a replay: client-only stand-ins for the mobs, other players and projectiles that were there, moved
 * frame by frame, plus gun tracers and explosions. While one plays, the real mobs, players and projectiles are hidden
 * on this screen only (nothing in the world moves), and they're back the moment it stops.
 */
public final class ReplayActors {
	private static final EquipmentSlot[] GEAR_SLOTS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
			EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	/** Stand-ins (and the replay's own ghost/camera): never hidden. */
	private static final Set<Entity> exempt = Collections.newSetFromMap(new IdentityHashMap<>());
	private static final List<ReplayActors> playing = new ArrayList<>();
	private static int nextId = -0x600000;

	private final ClientLevel level;
	private final Payloads.Scene scene;
	private final Entity[] copies;
	private final boolean[] done;
	private int lastFrame = -1;
	private double prevIdx;
	private double idx;

	ReplayActors(ClientLevel level, Payloads.Scene scene) {
		this.level = level;
		this.scene = scene == null ? Payloads.Scene.EMPTY : scene;
		this.copies = new Entity[this.scene.actors().size()];
		this.done = new boolean[copies.length];
		playing.add(this);
	}

	static void exempt(Entity e) {
		if (e != null) {
			exempt.add(e);
		}
	}

	static void unexempt(Entity e) {
		if (e != null) {
			exempt.remove(e);
		}
	}

	/** Mixin (EntityRenderDispatcher): hide the real thing while a replay stands in for it. */
	public static boolean hides(Entity e) {
		if (playing.isEmpty() || exempt.contains(e) || e instanceof ArmorStand) {
			return false;
		}
		return e instanceof LivingEntity || e instanceof Projectile || e instanceof net.minecraft.world.entity.item.PrimedTnt;
	}

	/** Puts everything where it was at (fractional) frame `at` of the clip. Call once a tick, going forwards. */
	void show(double at) {
		prevIdx = idx;
		idx = at;
		int fi = (int) Math.floor(at);
		List<Payloads.Actor> actors = scene.actors();
		for (int i = 0; i < actors.size(); i++) {
			Payloads.Actor a = actors.get(i);
			int start = a.start();
			int end = start + a.frames().size() - 1;
			if (done[i] || fi < start) {
				continue;
			}
			if (fi > end) {
				// gone (killed, picked up, hit something, or out of range): it goes too
				finish(i, a);
				continue;
			}
			if (a.kind() == Payloads.Scene.TRACER) {
				continue; // drawn in render()
			}
			double local = Math.min(at - start, a.frames().size() - 1);
			Payloads.ActorFrame f = Payloads.ActorFrame.lerp(a.frames(), local);
			Payloads.ActorFrame raw = a.frames().get((int) local);
			Entity e = copies[i];
			if (e == null) {
				e = spawn(a, f);
				if (e == null) {
					done[i] = true;
					continue;
				}
				copies[i] = e;
			} else {
				move(e, f);
			}
			look(e, raw);
			// swings, hits and deaths for every frame stepped over since last tick
			for (int j = Math.max(start, lastFrame + 1); j <= fi && j <= end; j++) {
				Payloads.ActorFrame step = a.frames().get(j - start);
				if (e instanceof LivingEntity l) {
					if (step.has(Payloads.Scene.SWING)) {
						l.swing(InteractionHand.MAIN_HAND);
					}
					if (step.has(Payloads.Scene.HURT)) {
						l.hurtTime = l.hurtDuration = 10;
					}
					if (step.has(Payloads.Scene.DEAD) && !l.isDeadOrDying()) {
						l.setHealth(0);
					}
				}
			}
		}
		for (Payloads.Blast b : scene.blasts()) {
			if (b.frame() > lastFrame && b.frame() <= fi) {
				level.addParticle(ParticleTypes.EXPLOSION_EMITTER, b.x(), b.y(), b.z(), 1, 0, 0);
				level.playLocalSound(b.x(), b.y(), b.z(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 3f, 0.9f, false);
			}
		}
		lastFrame = Math.max(lastFrame, fi);
	}

	private void finish(int i, Payloads.Actor a) {
		done[i] = true;
		Entity e = copies[i];
		copies[i] = null;
		if (e == null) {
			return;
		}
		if (e instanceof LivingEntity l && l.isDeadOrDying()) {
			for (int k = 0; k < 20; k++) {
				level.addParticle(ParticleTypes.POOF, e.getRandomX(1), e.getRandomY(), e.getRandomZ(1),
						e.getRandom().nextGaussian() * 0.02, e.getRandom().nextGaussian() * 0.02, e.getRandom().nextGaussian() * 0.02);
			}
		}
		remove(e);
	}

	private Entity spawn(Payloads.Actor a, Payloads.ActorFrame f) {
		Entity e;
		try {
			if (a.kind() == Payloads.Scene.PLAYER) {
				KillcamReplay.Ghost g = new KillcamReplay.Ghost(level, new GameProfile(UUID.randomUUID(), a.name()), a.player());
				for (int k = 0; k < GEAR_SLOTS.length && k < a.gear().size(); k++) {
					g.setItemSlot(GEAR_SLOTS[k], a.gear().get(k));
				}
				e = g;
			} else {
				ResourceLocation id = ResourceLocation.tryParse(a.type());
				EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
				e = type == null ? null : type.create(level);
				if (e == null) {
					return null;
				}
				if (a.data() != null && !a.data().isEmpty()) {
					try {
						e.load(a.data());
					} catch (Throwable ignored) {
						// some modded mob didn't like it: still show one, just plain
					}
				}
			}
			e.setId(nextId--);
			e.setUUID(UUID.randomUUID());
			e.setSilent(true);
			e.setNoGravity(true);
			if (e instanceof Mob m) {
				m.setNoAi(true);
			}
			e.moveTo(f.x(), f.y(), f.z(), f.yaw(), f.pitch());
			if (e instanceof LivingEntity l) {
				l.setYHeadRot(f.headYaw());
				l.setYBodyRot(f.yaw());
			}
			e.setDeltaMovement(f.vx(), f.vy(), f.vz());
			exempt.add(e);
			level.addEntity(e);
			return e;
		} catch (Throwable t) {
			return null;
		}
	}

	private static void move(Entity e, Payloads.ActorFrame f) {
		if (e instanceof LivingEntity l) {
			l.lerpTo(f.x(), f.y(), f.z(), f.yaw(), f.pitch(), 1);
			l.lerpHeadTo(f.headYaw(), 1);
		} else {
			// projectiles move themselves a bit each tick: put them back where they really were (keeps it smooth)
			e.setDeltaMovement(f.vx(), f.vy(), f.vz());
			e.setPos(f.x(), f.y(), f.z());
			e.setYRot(f.yaw());
			e.setXRot(f.pitch());
		}
	}

	private static void look(Entity e, Payloads.ActorFrame raw) {
		if (e instanceof LivingEntity) {
			e.setPose(raw.has(Payloads.Scene.SWIM) ? Pose.SWIMMING
					: raw.has(Payloads.Scene.GLIDE) ? Pose.FALL_FLYING
					: raw.has(Payloads.Scene.CROUCH) ? Pose.CROUCHING : Pose.STANDING);
			e.setShiftKeyDown(raw.has(Payloads.Scene.CROUCH));
		}
		e.setInvisible(raw.has(Payloads.Scene.INVISIBLE));
		e.setSharedFlagOnFire(raw.has(Payloads.Scene.FIRE));
		if (e instanceof Creeper c) {
			c.setSwellDir(raw.has(Payloads.Scene.IGNITED) ? 1 : -1);
		}
	}

	private void remove(Entity e) {
		exempt.remove(e);
		if (e.level() == level) {
			level.removeEntity(e.getId(), Entity.RemovalReason.DISCARDED);
		}
	}

	/** Removes every stand-in; the real ones show again. */
	void stop() {
		for (int i = 0; i < copies.length; i++) {
			if (copies[i] != null) {
				remove(copies[i]);
				copies[i] = null;
			}
		}
		playing.remove(this);
	}

	static void stopAll() {
		for (ReplayActors r : new ArrayList<>(playing)) {
			r.stop();
		}
		exempt.clear();
	}

	/** Gun bullets: a bright streak along the tick it travelled. */
	static void render(PoseStack pose, Camera camera, float partial) {
		if (playing.isEmpty()) {
			return;
		}
		Vec3 cam = camera.getPosition();
		Matrix4f m = pose.last().pose();
		BufferBuilder buf = null;
		for (ReplayActors r : playing) {
			double at = r.prevIdx + (r.idx - r.prevIdx) * partial;
			for (Payloads.Actor a : r.scene.actors()) {
				if (a.kind() != Payloads.Scene.TRACER || at < a.start() || at > a.start() + a.frames().size()) {
					continue;
				}
				Payloads.ActorFrame f = a.frames().get(Math.min(a.frames().size() - 1, (int) (at - a.start())));
				Vec3 v = new Vec3(f.vx(), f.vy(), f.vz());
				if (v.lengthSqr() < 1e-4) {
					continue;
				}
				Vec3 head = new Vec3(f.x(), f.y(), f.z());
				Vec3 tail = head.subtract(v);
				Vec3 toCam = cam.subtract(head);
				Vec3 side = v.cross(toCam);
				if (side.lengthSqr() < 1e-8) {
					continue;
				}
				side = side.normalize().scale(0.035);
				if (buf == null) {
					RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
					RenderSystem.enableBlend();
					RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
							com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
					RenderSystem.enableDepthTest();
					RenderSystem.depthMask(false);
					RenderSystem.disableCull();
					buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
				}
				Vec3 a1 = tail.subtract(side).subtract(cam);
				Vec3 a2 = tail.add(side).subtract(cam);
				Vec3 b1 = head.add(side).subtract(cam);
				Vec3 b2 = head.subtract(side).subtract(cam);
				buf.addVertex(m, (float) a1.x, (float) a1.y, (float) a1.z).setColor(1f, 0.75f, 0.3f, 0f);
				buf.addVertex(m, (float) a2.x, (float) a2.y, (float) a2.z).setColor(1f, 0.75f, 0.3f, 0f);
				buf.addVertex(m, (float) b1.x, (float) b1.y, (float) b1.z).setColor(1f, 0.95f, 0.6f, 0.95f);
				buf.addVertex(m, (float) b2.x, (float) b2.y, (float) b2.z).setColor(1f, 0.95f, 0.6f, 0.95f);
			}
		}
		if (buf != null) {
			MeshData mesh = buf.build();
			if (mesh != null) {
				BufferUploader.drawWithShader(mesh);
			}
			RenderSystem.enableCull();
			RenderSystem.depthMask(true);
			RenderSystem.defaultBlendFunc();
			RenderSystem.disableBlend();
		}
	}
}
