package moonahmc.bubble;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything around the players for the last few seconds, so a replay (down cam, kill cam) can show the whole scene:
 * the mobs and other players where they were, arrows and fireballs in flight, gun tracers and explosions. Nothing in
 * the world is ever moved: the clients play it back with their own stand-ins.
 */
final class ReplayScene {
	/** Same length as the players' own tracks (BubbleServer.TRACK_FRAMES). */
	private static final int FRAMES = 60;
	/** Recorded around every player. */
	private static final double RANGE = 48;
	/** Shown in a clip: this close to the player it's about, at that moment (whatever hurt them is always in). */
	private static final double CLIP_RANGE = 44;
	/** Whatever last hurt a player is recorded out to here (a Watcher kills from 64 blocks). */
	private static final double ATTACKER_RANGE = 96;
	private static final int MAX_PER_TICK = 160;
	private static final int MAX_DATA_BYTES = 6000;
	private static final EquipmentSlot[] GEAR_SLOTS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
			EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	/** Big or useless on the client (and UUID would clash with the real one). */
	private static final Set<String> DROP = Set.of("UUID", "Pos", "Motion", "Rotation", "Brain", "Attributes", "Offers",
			"Gossips", "Inventory", "Passengers", "leash", "Leash", "FallDistance", "Air", "PortalCooldown", "Xp",
			"RestocksToday", "LastRestock", "sleeping_pos", "Owner", "HurtByTimestamp");
	/** If what's left is still too big, keep only what changes how it looks. */
	private static final Set<String> LOOKS = Set.of("HandItems", "ArmorItems", "body_armor_item", "CustomName",
			"CustomNameVisible", "IsBaby", "Size", "Variant", "variant", "Color", "Type", "VillagerData", "powered",
			"Saddle", "Sheared", "Glowing", "Invisible", "item", "Item");
	/** Can't be stood in for on the client (bits of other entities). */
	private static final Set<String> SKIP_TYPES = Set.of("minecraft:ender_dragon");

	private record Sample(int id, double x, double y, double z, float yaw, float pitch, float headYaw, float vx, float vy,
						  float vz, int flags) {
	}

	private record Snap(long tick, List<Sample> samples) {
	}

	private record BlastAt(ResourceKey<Level> level, long tick, Vec3 pos) {
	}

	/** What an entity is (and what it looks like), from the first time it was seen. */
	private static final class Info {
		byte kind;
		String type;
		CompoundTag data;
		UUID player;
		String name = "";
		List<ItemStack> gear = List.of();
		long lastSeen;
		int lastHurt;
	}

	private static final Map<ResourceKey<Level>, ArrayDeque<Snap>> snaps = new HashMap<>();
	private static final Map<Integer, Info> infos = new HashMap<>();
	private static final List<BlastAt> blasts = new ArrayList<>();
	private static long lastTick;

	private ReplayScene() {
	}

	static void init() {
		NeoForge.EVENT_BUS.addListener((ExplosionEvent.Detonate e) -> {
			if (e.getLevel() instanceof ServerLevel level) {
				// fires while the world ticks, before this tick's recording
				blasts.add(new BlastAt(level.dimension(), lastTick + 1, e.getExplosion().center()));
			}
		});
	}

	static void clear() {
		snaps.clear();
		infos.clear();
		blasts.clear();
	}

	// /bubble replaytest: records around a stand-in for 3 seconds, then builds its clip and sends it through the codec
	private static ServerPlayer testSubject;
	private static net.minecraft.commands.CommandSourceStack testSource;
	private static final List<Payloads.Frame> testFrames = new ArrayList<>();

	static void startTest(net.minecraft.commands.CommandSourceStack src, ServerLevel level, Vec3 pos) {
		testSubject = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
		testSubject.setPos(pos.x, pos.y, pos.z);
		testSource = src;
		testFrames.clear();
	}

	private static void tickTest(MinecraftServer server) {
		if (testSubject == null) {
			return;
		}
		testFrames.add(new Payloads.Frame(testSubject.getX(), testSubject.getY(), testSubject.getZ(), 0, 0, 0, (byte) 0));
		if (testFrames.size() < FRAMES) {
			return;
		}
		ServerPlayer subject = testSubject;
		testSubject = null;
		Payloads.Scene scene = clip(subject, List.copyOf(testFrames));
		io.netty.buffer.ByteBuf raw = io.netty.buffer.Unpooled.buffer();
		net.minecraft.network.RegistryFriendlyByteBuf buf = new net.minecraft.network.RegistryFriendlyByteBuf(raw, server.registryAccess());
		Payloads.Killcam k = new Payloads.Killcam(subject.getUUID(), "test", subject.serverLevel().dimension().location().toString(),
				List.copyOf(testFrames), List.of(), scene);
		Payloads.Killcam.CODEC.encode(buf, k);
		int bytes = buf.readableBytes();
		Payloads.Killcam back = Payloads.Killcam.CODEC.decode(buf);
		Map<String, Integer> kinds = new java.util.TreeMap<>();
		int frames = 0;
		double err = 0;
		for (int i = 0; i < scene.actors().size(); i++) {
			Payloads.Actor a = scene.actors().get(i);
			Payloads.Actor b = back.scene().actors().get(i);
			kinds.merge(a.kind() + ":" + a.type(), 1, Integer::sum);
			frames += a.frames().size();
			for (int j = 0; j < a.frames().size(); j++) {
				err = Math.max(err, Math.abs(a.frames().get(j).x() - b.frames().get(j).x()) + Math.abs(a.frames().get(j).z() - b.frames().get(j).z()));
			}
		}
		String msg = "replaytest: actors=" + scene.actors().size() + " decoded=" + back.scene().actors().size() + " frames=" + frames
				+ " blasts=" + scene.blasts().size() + " bytes=" + bytes + " left=" + buf.readableBytes()
				+ " maxPosErr=" + String.format(java.util.Locale.ROOT, "%.4f", err) + " kinds=" + kinds;
		testSource.sendSuccess(() -> net.minecraft.network.chat.Component.literal(msg), false);
		Bubble.LOG.info(msg);
	}

	/** Called once a tick (after the world moved) with the players whose replays might be wanted. */
	static void record(MinecraftServer server, long tick, List<ServerPlayer> watched) {
		lastTick = tick;
		if (testSubject != null) {
			watched = new ArrayList<>(watched);
			watched.add(testSubject);
		}
		Map<ServerLevel, List<ServerPlayer>> byLevel = new HashMap<>();
		for (ServerPlayer p : watched) {
			byLevel.computeIfAbsent(p.serverLevel(), k -> new ArrayList<>()).add(p);
		}
		for (Map.Entry<ServerLevel, List<ServerPlayer>> e : byLevel.entrySet()) {
			ServerLevel level = e.getKey();
			Map<Integer, Entity> found = new LinkedHashMap<>();
			Map<Integer, Double> near = new HashMap<>();
			for (ServerPlayer p : e.getValue()) {
				AABB box = p.getBoundingBox().inflate(RANGE);
				for (Entity x : level.getEntities((Entity) null, box, ReplayScene::wanted)) {
					double d = x.distanceToSqr(p);
					found.putIfAbsent(x.getId(), x);
					near.merge(x.getId(), d, Math::min);
				}
				// whoever is hurting them, even from further off (a sniper, a Watcher): first in line
				LivingEntity by = p.getLastHurtByMob();
				if (by != null && by.level() == level && wanted(by) && by.distanceToSqr(p) < ATTACKER_RANGE * ATTACKER_RANGE) {
					found.putIfAbsent(by.getId(), by);
					near.put(by.getId(), 0.0);
				}
			}
			List<Entity> list = new ArrayList<>(found.values());
			if (list.size() > MAX_PER_TICK) {
				list.sort(Comparator.comparingDouble(x -> near.get(x.getId())));
				list = list.subList(0, MAX_PER_TICK);
			}
			List<Sample> samples = new ArrayList<>(list.size());
			for (Entity x : list) {
				Info info = infos.get(x.getId());
				if (info == null) {
					info = describe(x);
					if (info == null) {
						continue;
					}
					infos.put(x.getId(), info);
				} else if (info.kind == Payloads.Scene.PLAYER && (tick + x.getId()) % 10 == 0) {
					info.gear = gearOf((LivingEntity) x);
				}
				info.lastSeen = tick;
				samples.add(sample(x, info));
			}
			ArrayDeque<Snap> q = snaps.computeIfAbsent(level.dimension(), k -> new ArrayDeque<>());
			q.addLast(new Snap(tick, samples));
			while (q.size() > FRAMES) {
				q.removeFirst();
			}
		}
		// worlds nobody is in any more, and things not seen for a while
		snaps.values().removeIf(q -> q.isEmpty() || q.peekLast().tick < tick - FRAMES);
		if (tick % 20 == 0) {
			infos.values().removeIf(i -> i.lastSeen < tick - FRAMES - 5);
		}
		blasts.removeIf(b -> b.tick < tick - FRAMES);
		tickTest(server);
	}

	private static boolean wanted(Entity e) {
		if (e.isRemoved()) {
			return false;
		}
		if (e instanceof ServerPlayer p) {
			return !p.isSpectator();
		}
		return (e instanceof LivingEntity && !(e instanceof ArmorStand)) || e instanceof Projectile
				|| e instanceof net.minecraft.world.entity.item.PrimedTnt;
	}

	private static Info describe(Entity e) {
		String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
		if (SKIP_TYPES.contains(type)) {
			return null;
		}
		Info i = new Info();
		i.type = type;
		if (e instanceof ServerPlayer p) {
			i.kind = Payloads.Scene.PLAYER;
			i.player = p.getUUID();
			i.name = p.getGameProfile().getName();
			i.gear = gearOf(p);
		} else if (e instanceof Projectile && type.startsWith("tacz:")) {
			i.kind = Payloads.Scene.TRACER; // gun bullets: drawn as tracers
		} else {
			i.kind = e instanceof Projectile ? Payloads.Scene.PROJECTILE : Payloads.Scene.MOB;
			i.data = looks(e);
		}
		if (e instanceof LivingEntity l) {
			i.lastHurt = l.hurtTime;
		}
		return i;
	}

	private static List<ItemStack> gearOf(LivingEntity e) {
		List<ItemStack> worn = new ArrayList<>(GEAR_SLOTS.length);
		for (EquipmentSlot slot : GEAR_SLOTS) {
			worn.add(e.getItemBySlot(slot).copy());
		}
		return worn;
	}

	/** The entity's saved data, minus the bits the client doesn't need. */
	private static CompoundTag looks(Entity e) {
		CompoundTag tag = new CompoundTag();
		try {
			e.saveWithoutId(tag);
		} catch (Throwable t) {
			return null;
		}
		for (String k : DROP) {
			tag.remove(k);
		}
		if (tag.sizeInBytes() > MAX_DATA_BYTES) {
			CompoundTag small = new CompoundTag();
			for (String k : LOOKS) {
				if (tag.contains(k)) {
					small.put(k, tag.get(k));
				}
			}
			tag = small.sizeInBytes() > MAX_DATA_BYTES ? new CompoundTag() : small;
		}
		return tag;
	}

	private static Sample sample(Entity x, Info info) {
		int flags = 0;
		float headYaw = x.getYHeadRot();
		if (x instanceof LivingEntity l) {
			if (l.swinging && l.swingTime <= 1) {
				flags |= Payloads.Scene.SWING;
			}
			if (l.hurtTime > 0 && l.hurtTime > info.lastHurt) {
				flags |= Payloads.Scene.HURT;
			}
			info.lastHurt = l.hurtTime;
			if (l.isDeadOrDying()) {
				flags |= Payloads.Scene.DEAD;
			}
		}
		if (x.isCrouching()) {
			flags |= Payloads.Scene.CROUCH;
		}
		if (x.getPose() == Pose.SWIMMING) {
			flags |= Payloads.Scene.SWIM;
		}
		if (x.getPose() == Pose.FALL_FLYING) {
			flags |= Payloads.Scene.GLIDE;
		}
		if (x instanceof Creeper c && c.getSwellDir() > 0) {
			flags |= Payloads.Scene.IGNITED;
		}
		if (x.isInvisible()) {
			flags |= Payloads.Scene.INVISIBLE;
		}
		if (x.isOnFire()) {
			flags |= Payloads.Scene.FIRE;
		}
		Vec3 v = x.getDeltaMovement();
		return new Sample(x.getId(), x.getX(), x.getY(), x.getZ(), x.getYRot(), x.getXRot(), headYaw, (float) v.x,
				(float) v.y, (float) v.z, flags);
	}

	/**
	 * Everything around `subject` during their clip (`frames`, which end on the latest recorded tick): each mob,
	 * player and projectile, frame by frame, plus explosions.
	 */
	static Payloads.Scene clip(ServerPlayer subject, List<Payloads.Frame> frames) {
		return clip(subject, frames, Set.of());
	}

	/** The same, with `focus` (entity ids, e.g. the boss) always in however far away. */
	static Payloads.Scene clip(ServerPlayer subject, List<Payloads.Frame> frames, Set<Integer> focus) {
		ArrayDeque<Snap> q = snaps.get(subject.serverLevel().dimension());
		int n = frames.size();
		if (q == null || n < 2) {
			return Payloads.Scene.EMPTY;
		}
		Payloads.Frame origin = frames.get(0);
		long firstTick = lastTick - (n - 1);
		ServerLevel level = subject.serverLevel();
		// what hurt them: in the clip however far away it was, and never cut for space
		Set<Integer> attackers = new java.util.HashSet<>(focus);
		if (subject.getLastHurtByMob() != null) {
			attackers.add(subject.getLastHurtByMob().getId());
		}
		if (subject.getKillCredit() != null) {
			attackers.add(subject.getKillCredit().getId());
		}
		Map<Integer, Sample[]> tracks = new LinkedHashMap<>();
		Map<Integer, Double> closest = new HashMap<>();
		for (Snap s : q) {
			int fi = (int) (s.tick - firstTick);
			if (fi < 0 || fi >= n) {
				continue;
			}
			Payloads.Frame f = frames.get(fi);
			for (Sample a : s.samples) {
				if (a.id == subject.getId()) {
					continue;
				}
				double dx = a.x - f.x();
				double dy = a.y - f.y();
				double dz = a.z - f.z();
				double d = dx * dx + dy * dy + dz * dz;
				if (attackers.contains(a.id)) {
					d = 0;
				} else if (d > CLIP_RANGE * CLIP_RANGE) {
					continue;
				}
				tracks.computeIfAbsent(a.id, k -> new Sample[n])[fi] = a;
				closest.merge(a.id, d, Math::min);
			}
		}
		List<Integer> ids = new ArrayList<>(tracks.keySet());
		ids.removeIf(id -> !infos.containsKey(id));
		ids.sort(Comparator.comparingDouble(closest::get));
		if (ids.size() > Payloads.Scene.MAX_ACTORS) {
			ids = ids.subList(0, Payloads.Scene.MAX_ACTORS);
		}
		List<Payloads.Actor> actors = new ArrayList<>(ids.size());
		for (int id : ids) {
			Info info = infos.get(id);
			Sample[] track = tracks.get(id);
			int start = 0;
			while (start < n && track[start] == null) {
				start++;
			}
			int end = n - 1;
			while (end > start && track[end] == null) {
				end--;
			}
			List<Payloads.ActorFrame> out = new ArrayList<>(end - start + 1);
			Sample last = track[start];
			for (int i = start; i <= end; i++) {
				Sample a = track[i] != null ? track[i] : last;
				// a gap (it stepped out of range for a moment): hold where it was, without repeating its swings/hits
				int flags = track[i] != null ? a.flags : a.flags & ~(Payloads.Scene.SWING | Payloads.Scene.HURT);
				last = a;
				out.add(new Payloads.ActorFrame(a.x, a.y, a.z, a.yaw, a.pitch, a.headYaw, a.vx, a.vy, a.vz, flags));
			}
			CompoundTag data = info.data;
			Entity live = level.getEntity(id);
			if (live != null && info.kind != Payloads.Scene.PLAYER && info.kind != Payloads.Scene.TRACER && !live.isRemoved()) {
				CompoundTag fresh = looks(live); // what it looks like now (it may have picked something up)
				if (fresh != null) {
					data = fresh;
				}
			}
			actors.add(new Payloads.Actor(info.kind, info.type, data, info.player, info.name, info.gear, start, out, id));
		}
		List<Payloads.Blast> booms = new ArrayList<>();
		for (BlastAt b : blasts) {
			int fi = (int) (b.tick - firstTick);
			if (b.level == level.dimension() && fi >= 0 && fi < n) {
				Payloads.Frame f = frames.get(fi);
				if (b.pos.distanceToSqr(f.x(), f.y(), f.z()) < CLIP_RANGE * CLIP_RANGE * 2) {
					booms.add(new Payloads.Blast(fi, b.pos.x, b.pos.y, b.pos.z));
				}
			}
		}
		return new Payloads.Scene(origin.x(), origin.y(), origin.z(), actors, booms);
	}
}
