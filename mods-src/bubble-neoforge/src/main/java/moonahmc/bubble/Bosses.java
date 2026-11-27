package moonahmc.bubble;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Big bosses (Cataclysm's Ignis, The Harbinger, The Leviathan...): everyone who helped gets the reward, not just
 * whoever landed the last hit. A boss is a "mobDrops" entry with "boss": true (config/bubble-cases.json); each helper
 * gets its Strange Matter and Eidolons, a Boss Case (1 in 4 also a Wheel Spin) and a boss kill in the hall of fame.
 * Helpers are the players who hurt it, plus anyone fighting near it when it died. The server hears who won, and the
 * group gets a short replay of the last hit (the boss cam, client/DownCam).
 * <p>
 * Also: Cataclysm's mini-bosses sometimes come as Apotheosis elites (even the ones built into structures), and the
 * mid-run wheel's "Summon a boss" (a boss shows up near one of you after a warning).
 */
final class Bosses {
	/** Anyone this close when it dies counts as having helped. */
	private static final double NEAR = 48;
	/** Players who hurt it are remembered this long (ticks) without hitting it again. */
	private static final long FORGET = 20 * 60 * 10;
	/** The boss cam waits this long after the kill, so the replay shows the boss going down. */
	private static final int CAM_DELAY = 12;
	/** How often a mini-boss that wasn't rolled as an elite when it spawned (one built into a structure) becomes one. */
	private static final double MINIBOSS_ELITE = 0.12;
	/** Cataclysm mini-bosses (with Apotheosis elites in data/bubble/apothic_elites). */
	static final Set<String> MINIBOSSES = Set.of("cataclysm:ender_golem", "cataclysm:kobolediator", "cataclysm:wadjet",
			"cataclysm:coralssus", "cataclysm:the_prowler", "cataclysm:aptrgangr", "cataclysm:amethyst_crab",
			"cataclysm:clawdian", "cataclysm:ignited_berserker");
	private static final String ROLLED = "bubble.elite_rolled";

	private static final class Fight {
		final Map<UUID, Long> hitBy = new HashMap<>();
		long started;
		long last;
	}

	private record Cam(UUID killer, int boss, String name, long due) {
	}

	private record Summon(UUID target, String entity, long due) {
	}

	private static final Map<Integer, Fight> fights = new HashMap<>();
	private static final List<Cam> cams = new ArrayList<>();
	private static final List<Summon> summons = new ArrayList<>();
	private static final List<Mob> eliteQueue = new ArrayList<>();
	private static MinecraftServer server;

	private Bosses() {
	}

	/** The "mobDrops" entry for this mob if it's a boss (shared rewards), or null. */
	/** Someone is in a boss fight right now (the director keeps quiet). */
	static boolean fighting() {
		return !fights.isEmpty();
	}

	static JsonObject entry(Entity e) {
		JsonObject cfg = Gamble.mobDrops();
		if (cfg == null || !cfg.has("mobs")) {
			return null;
		}
		String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
		JsonObject mobs = cfg.getAsJsonObject("mobs");
		if (!mobs.has(id) || !mobs.get(id).isJsonObject()) {
			return null;
		}
		JsonObject m = mobs.getAsJsonObject(id);
		return m.has("boss") && m.get("boss").getAsBoolean() ? m : null;
	}

	static boolean handles(Entity e) {
		return entry(e) != null;
	}

	private static long now() {
		return server == null ? 0 : server.getTickCount();
	}

	static void init() {
		var bus = NeoForge.EVENT_BUS;
		bus.addListener((net.neoforged.neoforge.event.server.ServerStartedEvent e) -> {
			server = e.getServer();
			EnergyCompat.available(); // says in the log which mods' items the shop can charge
		});
		bus.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e) -> {
			server = null;
			fights.clear();
			cams.clear();
			summons.clear();
			eliteQueue.clear();
		});
		bus.addListener((LivingDamageEvent.Post e) -> {
			LivingEntity hurt = e.getEntity();
			if (hurt.level().isClientSide || !(e.getSource().getEntity() instanceof ServerPlayer p) || p instanceof net.neoforged.neoforge.common.util.FakePlayer
					|| entry(hurt) == null) {
				return;
			}
			Fight f = fights.computeIfAbsent(hurt.getId(), k -> {
				Fight n = new Fight();
				n.started = now();
				return n;
			});
			f.hitBy.put(p.getUUID(), now());
			f.last = now();
		});
		bus.addListener((LivingDeathEvent e) -> {
			LivingEntity dead = e.getEntity();
			if (dead.level().isClientSide) {
				return;
			}
			JsonObject m = entry(dead);
			Fight f = fights.remove(dead.getId());
			if (m == null || Lobby.active() || !BubbleServer.playing()) {
				return;
			}
			// a machine's fake player (turrets, auto-swords) doesn't count: whoever is near still gets it
			ServerPlayer killer = e.getSource().getEntity() instanceof ServerPlayer sp && !(sp instanceof net.neoforged.neoforge.common.util.FakePlayer) ? sp : null;
			defeated(dead, m, f, killer);
		});
		bus.addListener((EntityJoinLevelEvent e) -> {
			if (e.getLevel().isClientSide() || !(e.getEntity() instanceof Mob mob) || !net.neoforged.fml.ModList.get().isLoaded("apotheosis")) {
				return;
			}
			if (!MINIBOSSES.contains(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString())) {
				return;
			}
			CompoundTag pd = mob.getPersistentData();
			if (pd.getBoolean(ROLLED)) {
				return;
			}
			pd.putBoolean(ROLLED, true);
			if (!ApothElites.isElite(mob) && mob.getRandom().nextDouble() < MINIBOSS_ELITE) {
				eliteQueue.add(mob); // made an elite next tick (not while it's being added)
			}
		});
		bus.addListener((ServerTickEvent.Post e) -> tick(e.getServer()));
	}

	private static void tick(MinecraftServer s) {
		long t = now();
		if (!eliteQueue.isEmpty()) {
			List<Mob> todo = new ArrayList<>(eliteQueue);
			eliteQueue.clear();
			for (Mob mob : todo) {
				if (mob.isAlive() && mob.level() instanceof ServerLevel level && !ApothElites.isElite(mob)) {
					try {
						ApothElites.makeElite(level, mob);
					} catch (Throwable ex) {
						Bubble.LOG.warn("Bosses: couldn't make {} an elite", mob.getType(), ex);
					}
				}
			}
		}
		for (Cam c : new ArrayList<>(cams)) {
			if (t >= c.due) {
				cams.remove(c);
				ServerPlayer killer = s.getPlayerList().getPlayer(c.killer);
				if (killer != null) {
					BubbleServer.sendBossCam(killer, c.boss, c.name);
				}
			}
		}
		for (Summon su : new ArrayList<>(summons)) {
			if (t >= su.due) {
				summons.remove(su);
				ServerPlayer p = s.getPlayerList().getPlayer(su.target);
				if (p != null && BubbleServer.playing() && BubbleServer.counts(p)) {
					arrive(p, su.entity);
				}
			}
		}
		if (t % 200 == 0) {
			fights.values().removeIf(f -> t - f.last > FORGET);
		}
	}

	/** A boss died: everyone who helped is paid, the server hears about it, and the group gets the boss cam. */
	private static void defeated(LivingEntity boss, JsonObject m, Fight f, ServerPlayer killer) {
		MinecraftServer s = boss.getServer();
		if (s == null) {
			return;
		}
		Set<ServerPlayer> helpers = new LinkedHashSet<>();
		if (killer != null && BubbleServer.counts(killer)) {
			helpers.add(killer);
		}
		if (f != null) {
			for (UUID id : f.hitBy.keySet()) {
				ServerPlayer p = s.getPlayerList().getPlayer(id);
				if (p != null && BubbleServer.counts(p)) {
					helpers.add(p);
				}
			}
		}
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (p.level() == boss.level() && BubbleServer.counts(p) && p.distanceToSqr(boss) < NEAR * NEAR) {
				helpers.add(p);
			}
		}
		if (helpers.isEmpty()) {
			return; // nobody here (it fell in lava, or killed itself): nothing to hand out
		}
		double matter = m.has("matter") ? m.get("matter").getAsDouble() : 0;
		long eidolons = m.has("eidolons") ? m.get("eidolons").getAsLong() : 0;
		boolean bossCase = Gamble.exists("boss");
		Component name = boss.getType().getDescription();
		for (ServerPlayer p : helpers) {
			if (matter > 0) {
				Matter.earn(p, matter);
			}
			List<ItemStack> loot = new ArrayList<>();
			loot.add(bossCase ? CaseItem.of("boss", 1) : randomCase(p));
			if (p.getRandom().nextInt(4) == 0) {
				loot.add(new ItemStack(ModContent.WHEEL_SPIN.get()));
			}
			for (ItemStack st : loot) {
				if (st.isEmpty()) {
					continue;
				}
				if (!p.getInventory().add(st)) {
					p.drop(st, false);
				}
			}
			p.containerMenu.broadcastChanges();
			Eidolons.bossKill(p, eidolons, name.getString());
			MutableComponent sub = Component.literal("+" + Matter.format(matter) + " Strange Matter").withStyle(ChatFormatting.LIGHT_PURPLE);
			if (eidolons > 0) {
				sub.append(Component.literal(", +" + eidolons + " Eidolons").withStyle(ChatFormatting.LIGHT_PURPLE));
			}
			sub.append(Component.literal(", " + (bossCase ? "a Boss Case" : "a case")).withStyle(ChatFormatting.GOLD));
			BubbleServer.title(p, Component.literal("BOSS DEFEATED").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), sub, 5, 70, 20);
			p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 1f);
		}
		MutableComponent who = Component.empty();
		int i = 0;
		for (ServerPlayer p : helpers) {
			if (i > 0) {
				who.append(Component.literal(i == helpers.size() - 1 ? " and " : ", ").withStyle(ChatFormatting.GRAY));
			}
			who.append(p.getDisplayName());
			i++;
		}
		MutableComponent line = Component.literal("⚔ ").withStyle(ChatFormatting.GOLD)
				.append(name.copy().withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
				.append(Component.literal(" has fallen! ").withStyle(ChatFormatting.GOLD))
				.append(Component.literal("Taken down by ").withStyle(ChatFormatting.GRAY)).append(who);
		if (f != null && f.started > 0) {
			line.append(Component.literal(" in " + BubbleServer.formatDuration((int) Math.max(20, now() - f.started))).withStyle(ChatFormatting.GRAY));
		}
		s.getPlayerList().broadcastSystemMessage(line, false);
		RunLog.add(RunLog.EVENT, name.getString() + " was defeated");
		ShopWindows.bossDown(helpers, name.getString());
		for (ServerPlayer p : helpers) {
			if (p.getRandom().nextInt(3) == 0) { // 1 in 3 also get a Shop Key
				p.getInventory().placeItemBackInInventory(new ItemStack(ModContent.SHOP_KEY.get()));
			}
		}
		ServerPlayer camOf = killer != null && helpers.contains(killer) ? killer : helpers.iterator().next();
		cams.add(new Cam(camOf.getUUID(), boss.getId(), name.getString(), now() + CAM_DELAY));
	}

	private static ItemStack randomCase(ServerPlayer p) {
		List<String> ids = new ArrayList<>(Gamble.ids());
		return ids.isEmpty() ? ItemStack.EMPTY : CaseItem.of(ids.get(p.getRandom().nextInt(ids.size())), 1);
	}

	/**
	 * Ops, for testing without a fight: /bubble bosstest kill <mob> (a stand-in player "kills" one here and is paid)
	 * or /bubble bosstest summon <mob{nbt}> (the wheel's arrival, here).
	 */
	static int test(net.minecraft.commands.CommandSourceStack src, String what, String spec) {
		ServerLevel level = src.getLevel();
		ServerPlayer fake = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
		Vec3 at = src.getPosition();
		fake.moveTo(at.x, at.y, at.z);
		String out;
		if (what.equals("kill")) {
			Entity e = EntityType.byString(spec).map(t -> t.create(level)).orElse(null);
			if (!(e instanceof LivingEntity boss)) {
				src.sendFailure(Component.literal("No mob " + spec));
				return 0;
			}
			boss.moveTo(at.x + 6, at.y, at.z);
			level.addFreshEntity(boss);
			JsonObject m = entry(boss);
			double before = Matter.balance(fake);
			Fight f = new Fight();
			f.started = now() - 20 * 75;
			f.hitBy.put(fake.getUUID(), now());
			if (m != null) {
				try {
					defeated(boss, m, f, fake);
				} catch (Throwable t) {
					Bubble.LOG.error("bosstest: paying out failed", t);
				}
			}
			StringBuilder got = new StringBuilder();
			for (ItemStack st : fake.getInventory().items) {
				if (!st.isEmpty()) {
					got.append(' ').append(BuiltInRegistries.ITEM.getKey(st.getItem())).append(st.getComponents().has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)
							? st.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag().toString() : "").append('x').append(st.getCount());
				}
			}
			fake.getInventory().clearContent();
			boss.discard();
			out = "bosstest kill " + spec + ": boss=" + (m != null) + " matter=+" + Matter.format(Matter.balance(fake) - before) + " items=" + got
					+ " cams=" + cams.size() + " eidolons=" + Eidolons.balance(fake.getUUID());
			cams.clear();
		} else {
			arrive(fake, spec);
			String id = spec.contains("{") ? spec.substring(0, spec.indexOf('{')) : spec;
			var type = EntityType.byString(id).orElse(null);
			Entity found = type == null ? null : level.getEntities((Entity) null, new net.minecraft.world.phys.AABB(fake.blockPosition()).inflate(40),
					x -> x.getType() == type).stream().findFirst().orElse(null);
			out = "bosstest summon " + spec + ": " + (found == null ? "nothing there" : "at " + found.blockPosition().toShortString()
					+ " dist=" + String.format(java.util.Locale.ROOT, "%.1f", found.distanceTo(fake)) + " nbt=" + found.saveWithoutId(new CompoundTag()).toString().replaceAll("(?s).*?((Is_Act|is_Awaken|Power)[^,}]*).*", "$1"));
			if (found != null) {
				found.discard();
			}
		}
		String msg = out;
		src.sendSuccess(() -> Component.literal(msg), false);
		Bubble.LOG.info(msg);
		return 1;
	}

	// ------------------------------------------------------------------ wheel: "Summon a boss"

	/**
	 * {"type":"boss", "bosses":["cataclysm:ignis", "cataclysm:the_harbinger{Is_Act:1b}"], "warning":10, "distance":20}:
	 * one of those bosses turns up near a random player after a warning. Entries can carry NBT in braces.
	 */
	static void wheelBoss(List<ServerPlayer> players, JsonObject a) {
		List<ServerPlayer> playing = players.stream().filter(BubbleServer::counts).toList();
		if (playing.isEmpty()) {
			return;
		}
		List<String> pool = new ArrayList<>();
		if (a.has("bosses")) {
			for (JsonElement x : a.getAsJsonArray("bosses")) {
				String id = x.getAsString();
				String bare = id.contains("{") ? id.substring(0, id.indexOf('{')) : id;
				if (EntityType.byString(bare).isPresent()) {
					pool.add(id);
				}
			}
		}
		if (pool.isEmpty()) {
			pool.add("minecraft:warden"); // no Cataclysm: the Warden will do
		}
		ServerPlayer target = playing.get(playing.get(0).getRandom().nextInt(playing.size()));
		String pick = pool.get(target.getRandom().nextInt(pool.size()));
		int warning = a.has("warning") ? Math.max(0, a.get("warning").getAsInt()) : 10;
		for (ServerPlayer p : target.server.getPlayerList().getPlayers()) {
			BubbleServer.title(p, Component.literal("☠ A BOSS IS COMING ☠").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
					Component.literal("It's coming for " + target.getGameProfile().getName() + " in " + warning + "s. Get ready (or run).")
							.withStyle(ChatFormatting.RED), 5, Math.max(40, warning * 20), 10);
			p.playNotifySound(SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.6f, 0.8f);
		}
		summons.add(new Summon(target.getUUID(), a.has("distance") ? pick + "@" + a.get("distance").getAsInt() : pick, now() + warning * 20L));
	}

	/** Spawns the boss on the ground some way off from `p`, with a bang. */
	private static void arrive(ServerPlayer p, String spec) {
		int distance = 20;
		if (spec.contains("@")) {
			try {
				distance = Integer.parseInt(spec.substring(spec.lastIndexOf('@') + 1));
			} catch (NumberFormatException ignored) {
				// keep the default
			}
			spec = spec.substring(0, spec.lastIndexOf('@'));
		}
		String id = spec.contains("{") ? spec.substring(0, spec.indexOf('{')) : spec;
		CompoundTag nbt = new CompoundTag();
		if (spec.contains("{")) {
			try {
				nbt = TagParser.parseTag(spec.substring(spec.indexOf('{')));
			} catch (Exception ex) {
				Bubble.LOG.warn("Wheel: bad NBT in boss '{}'", spec);
			}
		}
		EntityType<?> type = EntityType.byString(id).orElse(null);
		if (type == null) {
			return;
		}
		ServerLevel level = p.serverLevel();
		var r = p.getRandom();
		BlockPos at = null;
		for (int tries = 0; tries < 16 && at == null; tries++) {
			double ang = r.nextDouble() * Math.PI * 2;
			double d = distance * (0.8 + r.nextDouble() * 0.4);
			at = ground(level, (int) Math.floor(p.getX() + Math.cos(ang) * d), p.getBlockY(), (int) Math.floor(p.getZ() + Math.sin(ang) * d));
		}
		if (at == null) {
			at = p.blockPosition().offset(0, 2, 0);
		}
		Entity e = type.create(level);
		if (e == null) {
			return;
		}
		if (!nbt.isEmpty()) {
			CompoundTag full = e.saveWithoutId(new CompoundTag());
			full.merge(nbt);
			e.load(full);
		}
		e.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, (float) Math.toDegrees(Math.atan2(p.getZ() - at.getZ(), p.getX() - at.getX())) - 90, 0);
		if (e instanceof Mob mob) {
			mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
			mob.setPersistenceRequired();
			mob.setTarget(p);
		}
		level.addFreshEntityWithPassengers(e);
		var bolt = EntityType.LIGHTNING_BOLT.create(level);
		if (bolt != null) {
			bolt.moveTo(Vec3.atBottomCenterOf(at));
			bolt.setVisualOnly(true);
			level.addFreshEntity(bolt);
		}
		Component name = e.getType().getDescription();
		p.server.getPlayerList().broadcastSystemMessage(Component.literal("☠ ").withStyle(ChatFormatting.DARK_RED)
				.append(name.copy().withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
				.append(Component.literal(" has come for ").withStyle(ChatFormatting.GRAY)).append(p.getDisplayName()), false);
		RunLog.add(RunLog.WHEEL, "The wheel summoned " + name.getString() + " on " + p.getGameProfile().getName());
	}

	/** Somewhere with room for something big: solid ground and 4 blocks of air, searching 8 up and 16 down. */
	private static BlockPos ground(ServerLevel level, int x, int y, int z) {
		for (int dy = 8; dy >= -16; dy--) {
			BlockPos pos = new BlockPos(x, y + dy, z);
			if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)) {
				continue;
			}
			boolean room = true;
			for (int h = 0; h < 4 && room; h++) {
				BlockPos up = pos.above(h);
				room = level.getBlockState(up).getCollisionShape(level, up).isEmpty() && level.getFluidState(up).isEmpty();
			}
			if (room) {
				return pos;
			}
		}
		return null;
	}

	/** The default bosses for the wheel slice (the ones that work away from their arenas). */
	static JsonArray wheelPool() {
		JsonArray a = new JsonArray();
		for (String s : new String[] {"cataclysm:ender_guardian", "cataclysm:netherite_monstrosity", "cataclysm:ignis",
				"cataclysm:maledictus", "cataclysm:ancient_remnant", "cataclysm:the_harbinger{Is_Act:1b}"}) {
			a.add(s);
		}
		return a;
	}

}
