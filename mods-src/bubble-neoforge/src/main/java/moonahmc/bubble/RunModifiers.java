package moonahmc.bubble;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Run-long modifiers from the start-of-run wheel (they last until the world ends), plus the attribute side of the
 * permanent Eidolon upgrades. Everything here is applied as attribute modifiers that are put back every couple of
 * seconds, so they follow players through deaths, Another life, dimension changes and logins.
 *
 *   low_gravity   everyone falls slowly and jumps high
 *   tiny          everyone is half size (mobs aren't)
 *   glass_cannon  half your max health, but every hit you deal does 5x damage (melee, bows, tridents, magic, modded weapons)
 *   lifesteal     every hit you deal heals you for 20% of the damage
 *   swap          every 5 minutes everyone swaps places (warning 3 seconds before)
 *   speedrun      kill any boss within 30 minutes: 500 Strange Matter each
 *   uhc           no natural health regen (gamerule), only potions, golden apples and lifesteal heal
 *   hard_world    the Apotheosis world tier goes straight to Ascent (tougher mobs, better loot)
 *   boss_bounty   the first boss killed (Cataclysm ones too) pays 1000 Strange Matter each
 *   double_loot   mobs killed by players drop everything twice
 *   eternal_night it's always midnight (daylight cycle off)
 *   miners_luck   permanent Haste II, and ores drop twice
 *   no_hunger     food never goes down, but it never heals you either
 *   pacifist      no hurting mobs for the first 10 minutes, then Strength I for the rest of the run
 *   explosive     every mob that dies goes off with a small (block-safe) explosion
 *   sky_island    everyone is sent to the Aether to start (with what they need to make a portal home)
 */
public final class RunModifiers {
	public static final String[] ALL = {"low_gravity", "tiny", "glass_cannon", "swap", "speedrun", "lifesteal", "uhc", "hard_world", "boss_bounty",
			"double_loot", "eternal_night", "miners_luck", "no_hunger", "pacifist", "explosive", "sky_island"};
	public static final long PACIFIST_TICKS = 12000;
	private static final int BOUNTY_REWARD = 1000;
	private static boolean bountyClaimed;
	/** explosive: deaths waiting to go off (done next tick, so a chain reaction spreads out instead of recursing). */
	private static final List<Vec3> pendingBlasts = new ArrayList<>();
	private static final List<ServerLevel> pendingLevels = new ArrayList<>();
	private static final long SWAP_EVERY = 6000;
	private static final long SWAP_WARN = 60;
	public static final long SPEEDRUN_TICKS = 36000;
	private static final int SPEEDRUN_REWARD = 500;
	public static final float GLASS_CANNON_DAMAGE = 5f;
	public static final float LIFESTEAL = 0.2f;
	private static final TagKey<EntityType<?>> BOSSES = Tags.EntityTypes.BOSSES;

	/** id -> server tick count (overworld game time) when it started. */
	private static final Map<String, Long> active = new LinkedHashMap<>();
	private static boolean speedrunClaimed;
	private static MinecraftServer server;
	private static Path file;

	private RunModifiers() {
	}

	static void init() {
		var bus = NeoForge.EVENT_BUS;
		bus.addListener((ServerStartedEvent e) -> {
			server = e.getServer();
			file = server.getWorldPath(LevelResource.ROOT).resolve("bubble-modifiers.json");
			load();
		});
		bus.addListener((ServerStoppedEvent e) -> server = null);
		bus.addListener((ServerTickEvent.Post e) -> tick(e.getServer()));
		bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
			if (e.getEntity() instanceof ServerPlayer p) {
				p.server.execute(() -> applyAttributes(p));
			}
		});
		bus.addListener((PlayerEvent.PlayerRespawnEvent e) -> {
			if (e.getEntity() instanceof ServerPlayer p) {
				p.server.execute(() -> applyAttributes(p));
			}
		});
		// Glass cannon: multiply the damage itself, so it covers bows, tridents, enchantments, crits and modded weapons
		// (an attack-damage attribute only touches bare melee). getEntity() is the shooter for projectiles.
		bus.addListener((LivingIncomingDamageEvent e) -> {
			if (!e.getEntity().level().isClientSide && pacifistOn() && e.getSource().getEntity() instanceof ServerPlayer attacker
					&& !(e.getEntity() instanceof net.minecraft.world.entity.player.Player)) {
				e.setCanceled(true);
				attacker.displayClientMessage(Component.literal("☮ Pacifist: no fighting for another " + clock(PACIFIST_TICKS - (now() - active.get("pacifist"))))
						.withStyle(ChatFormatting.GREEN), true);
			}
		});
		bus.addListener((net.neoforged.neoforge.event.entity.living.LivingDropsEvent e) -> {
			if (active.containsKey("double_loot") && e.getSource().getEntity() instanceof net.minecraft.world.entity.player.Player
					&& !(e.getEntity() instanceof net.minecraft.world.entity.player.Player)) {
				List<net.minecraft.world.entity.item.ItemEntity> extra = new ArrayList<>();
				for (var drop : e.getDrops()) {
					extra.add(new net.minecraft.world.entity.item.ItemEntity(drop.level(), drop.getX(), drop.getY(), drop.getZ(), drop.getItem().copy()));
				}
				e.getDrops().addAll(extra);
			}
		});
		bus.addListener((net.neoforged.neoforge.event.level.BlockDropsEvent e) -> {
			if (active.containsKey("miners_luck") && e.getBreaker() instanceof net.minecraft.world.entity.player.Player && e.getState().is(Tags.Blocks.ORES)) {
				List<net.minecraft.world.entity.item.ItemEntity> extra = new ArrayList<>();
				for (var drop : e.getDrops()) {
					extra.add(new net.minecraft.world.entity.item.ItemEntity(drop.level(), drop.getX(), drop.getY(), drop.getZ(), drop.getItem().copy()));
				}
				e.getDrops().addAll(extra);
			}
		});
		bus.addListener((LivingIncomingDamageEvent e) -> {
			if (!e.getEntity().level().isClientSide && active.containsKey("glass_cannon")
					&& e.getSource().getEntity() instanceof net.minecraft.world.entity.player.Player attacker && attacker != e.getEntity()) {
				e.setAmount(e.getAmount() * GLASS_CANNON_DAMAGE);
			}
		});
		// Lifesteal: heal for a share of the damage that actually landed (after armour)
		bus.addListener((net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post e) -> {
			if (!e.getEntity().level().isClientSide && active.containsKey("lifesteal") && e.getNewDamage() > 0
					&& e.getSource().getEntity() instanceof net.minecraft.world.entity.player.Player attacker && attacker != e.getEntity() && attacker.isAlive()) {
				attacker.heal(e.getNewDamage() * LIFESTEAL);
			}
		});
		bus.addListener((LivingDeathEvent e) -> {
			if (e.getEntity().level() instanceof ServerLevel level && active.containsKey("explosive") && e.getEntity() instanceof net.minecraft.world.entity.Mob
					&& !(e.getEntity() instanceof Swarmling) && pendingBlasts.size() < 256) {
				pendingBlasts.add(e.getEntity().position().add(0, e.getEntity().getBbHeight() / 2, 0));
				pendingLevels.add(level);
			}
			if (!e.getEntity().level().isClientSide && active.containsKey("boss_bounty") && !bountyClaimed && server != null && isBoss(e.getEntity())) {
				bountyClaimed = true;
				save();
				for (ServerPlayer p : server.getPlayerList().getPlayers()) {
					Matter.earn(p, BOUNTY_REWARD);
					BubbleServer.title(p, Component.literal("BOUNTY CLAIMED!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
							Component.literal(e.getEntity().getName().getString() + " is down: +" + BOUNTY_REWARD + " Strange Matter each").withStyle(ChatFormatting.AQUA), 5, 60, 15);
					p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 1f);
				}
				Goals.broadcast();
			}
			if (e.getEntity().level().isClientSide || !active.containsKey("speedrun") || speedrunClaimed || server == null) {
				return;
			}
			EntityType<?> type = e.getEntity().getType();
			if (type.is(BOSSES) || type == EntityType.WITHER || type == EntityType.ENDER_DRAGON || type == EntityType.WARDEN) {
				if (now() - active.get("speedrun") <= SPEEDRUN_TICKS) {
					speedrunClaimed = true;
					save();
					for (ServerPlayer p : server.getPlayerList().getPlayers()) {
						Matter.earn(p, SPEEDRUN_REWARD);
						BubbleServer.title(p, Component.literal("SPEEDRUN!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
								Component.literal("+" + SPEEDRUN_REWARD + " Strange Matter each").withStyle(ChatFormatting.AQUA), 5, 50, 15);
						p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 1f);
					}
					Goals.broadcast();
				}
			}
		});
	}

	/** Bosses: the boss tag (Cataclysm's are in it), plus the vanilla three to be sure. */
	private static boolean isBoss(net.minecraft.world.entity.LivingEntity e) {
		EntityType<?> type = e.getType();
		return type.is(BOSSES) || type == EntityType.WITHER || type == EntityType.ENDER_DRAGON || type == EntityType.WARDEN
				|| (BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace().equals("cataclysm") && e.getMaxHealth() >= 150);
	}

	static boolean pacifistOn() {
		Long start = active.get("pacifist");
		return start != null && now() - start < PACIFIST_TICKS;
	}

	public static boolean isActive(String id) {
		return active.containsKey(id);
	}

	/** Wheel action {"type":"modifier","id":"low_gravity"}. */
	static void activate(String id) {
		if (server == null || active.containsKey(id)) {
			return;
		}
		active.put(id, now());
		save();
		switchedOn(id);
		server.getPlayerList().getPlayers().forEach(RunModifiers::applyAttributes);
		Goals.broadcast();
		Bubble.LOG.info("Run modifier on: {}", id);
	}

	/** /bubble wheel clear: turn a modifier off (null = all of them). Returns how many were on. */
	static int deactivate(String id) {
		List<String> off = id == null ? new ArrayList<>(active.keySet()) : active.containsKey(id) ? List.of(id) : List.of();
		off.forEach(active::remove);
		if (server != null) {
			off.forEach(RunModifiers::switchedOff);
		}
		if (!off.isEmpty()) {
			save();
			if (server != null) {
				server.getPlayerList().getPlayers().forEach(RunModifiers::applyAttributes);
			}
			Goals.broadcast();
			Bubble.LOG.info("Run modifier(s) off: {}", off);
		}
		return off.size();
	}

	private static void switchedOn(String id) {
		var rules = server.getGameRules();
		switch (id) {
			case "uhc" -> rules.getRule(net.minecraft.world.level.GameRules.RULE_NATURAL_REGENERATION).set(false, server);
			case "eternal_night" -> {
				rules.getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, server);
				for (ServerLevel level : server.getAllLevels()) {
					long day = level.getDayTime() / 24000L;
					level.setDayTime(day * 24000L + 18000L);
				}
			}
			case "hard_world" -> {
				if (net.neoforged.fml.ModList.get().isLoaded("apotheosis")) {
					ApothTiers.hardStart();
				}
			}
			case "sky_island" -> SkyIsland.send(server);
			default -> {
			}
		}
	}

	private static void switchedOff(String id) {
		var rules = server.getGameRules();
		switch (id) {
			case "uhc" -> rules.getRule(net.minecraft.world.level.GameRules.RULE_NATURAL_REGENERATION).set(true, server);
			case "eternal_night" -> rules.getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(true, server);
			default -> {
			}
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (id.equals("miners_luck")) {
				removeOurEffect(p, net.minecraft.world.effect.MobEffects.DIG_SPEED);
			}
			if (id.equals("pacifist")) {
				removeOurEffect(p, net.minecraft.world.effect.MobEffects.DAMAGE_BOOST);
			}
		}
	}

	/** Takes off an effect we keep topped up (it's ours if it's ambient with no particles and under 30 s left). */
	private static void removeOurEffect(ServerPlayer p, Holder<net.minecraft.world.effect.MobEffect> effect) {
		var inst = p.getEffect(effect);
		if (inst != null && inst.isAmbient() && !inst.isVisible() && inst.getDuration() <= 600) {
			p.removeEffect(effect);
		}
	}

	private static void topUp(ServerPlayer p, Holder<net.minecraft.world.effect.MobEffect> effect, int amp) {
		var inst = p.getEffect(effect);
		if (inst == null || (inst.isAmbient() && inst.getDuration() < 200 && inst.getAmplifier() <= amp)) {
			p.addEffect(new net.minecraft.world.effect.MobEffectInstance(effect, 400, amp, true, false, true));
		}
	}

	static List<String> activeIds() {
		return new ArrayList<>(active.keySet());
	}

	/** For the goals HUD: "Speedrun: 23:41 left" style lines. */
	static List<String> hudLines() {
		List<String> out = new ArrayList<>();
		for (String id : active.keySet()) {
			String line = switch (id) {
				case "low_gravity" -> "Low gravity";
				case "tiny" -> "Tiny mode";
				case "glass_cannon" -> "Glass cannon";
				case "lifesteal" -> "Lifesteal";
				case "uhc" -> "UHC: no natural regen";
				case "hard_world" -> "Hard world";
				case "boss_bounty" -> bountyClaimed ? "Boss bounty: claimed!" : "Boss bounty: " + BOUNTY_REWARD + " for the first boss";
				case "double_loot" -> "Double loot";
				case "eternal_night" -> "Eternal night";
				case "miners_luck" -> "Miner's luck";
				case "no_hunger" -> "No hunger";
				case "pacifist" -> pacifistOn() ? "Pacifist: " + clock(PACIFIST_TICKS - (now() - active.get(id))) + " left" : "Pacifist: Strength unlocked";
				case "explosive" -> "Explosive mobs";
				case "sky_island" -> "Sky island start";
				case "swap" -> "Swap in " + clock(SWAP_EVERY - (now() - active.get(id)) % SWAP_EVERY);
				case "speedrun" -> speedrunClaimed ? "Speedrun: done!" : now() - active.get(id) > SPEEDRUN_TICKS ? "Speedrun: missed"
						: "Speedrun: kill a boss, " + clock(SPEEDRUN_TICKS - (now() - active.get(id)));
				default -> id;
			};
			out.add(line);
		}
		return out;
	}

	static String clock(long ticks) {
		long s = Math.max(0, ticks / 20);
		return String.format("%d:%02d", s / 60, s % 60);
	}

	private static long now() {
		return server == null ? 0 : server.overworld().getGameTime();
	}

	private static void tick(MinecraftServer s) {
		long t = s.getTickCount();
		if (t % 40 == 0) {
			s.getPlayerList().getPlayers().forEach(RunModifiers::applyAttributes);
		}
		if (!pendingBlasts.isEmpty()) {
			List<Vec3> blasts = new ArrayList<>(pendingBlasts);
			List<ServerLevel> levels = new ArrayList<>(pendingLevels);
			pendingBlasts.clear();
			pendingLevels.clear();
			for (int i = 0; i < blasts.size() && i < 64; i++) {
				Vec3 at = blasts.get(i);
				levels.get(i).explode(null, at.x, at.y, at.z, 1.6f, net.minecraft.world.level.Level.ExplosionInteraction.NONE);
			}
		}
		if (t % 20 == 0 && !Lobby.active()) {
			for (ServerPlayer p : s.getPlayerList().getPlayers()) {
				if (!BubbleServer.counts(p)) {
					continue;
				}
				if (active.containsKey("miners_luck")) {
					topUp(p, net.minecraft.world.effect.MobEffects.DIG_SPEED, 1);
				}
				if (active.containsKey("pacifist") && !pacifistOn()) {
					topUp(p, net.minecraft.world.effect.MobEffects.DAMAGE_BOOST, 0);
				}
				if (active.containsKey("no_hunger")) {
					var food = p.getFoodData();
					food.setFoodLevel(17); // above the sprint limit, below the 18 natural regen needs
					food.setSaturation(0);
					food.setExhaustion(0);
				}
			}
		}
		if (t % 200 == 0 && active.containsKey("eternal_night")) {
			for (ServerLevel level : s.getAllLevels()) {
				long time = level.getDayTime() % 24000L;
				if (time < 13000 || time > 23000) {
					level.setDayTime(level.getDayTime() / 24000L * 24000L + 18000L);
				}
			}
		}
		if (t % 20 == 0 && !active.isEmpty()) {
			Goals.broadcast(); // timers on the HUD
		}
		Long swapStart = active.get("swap");
		if (swapStart != null && !Lobby.active()) {
			long since = now() - swapStart;
			long left = SWAP_EVERY - since % SWAP_EVERY;
			if (left == SWAP_WARN || left == 40 || left == 20) {
				for (ServerPlayer p : s.getPlayerList().getPlayers()) {
					BubbleServer.title(p, Component.literal("Swap in " + left / 20 + "...").withStyle(ChatFormatting.LIGHT_PURPLE), Component.empty(), 0, 22, 0);
					p.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1f, 0.8f + (3 - left / 20) * 0.2f);
				}
			}
			if (since > 0 && since % SWAP_EVERY == 0) {
				swapEveryone(s);
			}
		}
	}

	private static void swapEveryone(MinecraftServer s) {
		List<ServerPlayer> players = new ArrayList<>(s.getPlayerList().getPlayers().stream().filter(BubbleServer::counts).toList());
		if (players.size() < 2) {
			return;
		}
		record Spot(ServerLevel level, Vec3 pos, float yaw, float pitch) {
		}
		List<Spot> spots = new ArrayList<>();
		for (ServerPlayer p : players) {
			spots.add(new Spot(p.serverLevel(), p.position(), p.getYRot(), p.getXRot()));
		}
		// Rotate by a random amount so nobody keeps their own spot
		int shift = 1 + s.overworld().random.nextInt(players.size() - 1);
		Collections.rotate(spots, shift);
		for (int i = 0; i < players.size(); i++) {
			ServerPlayer p = players.get(i);
			Spot to = spots.get(i);
			if (p.isPassenger()) {
				p.stopRiding();
			}
			p.teleportTo(to.level, to.pos.x, to.pos.y, to.pos.z, to.yaw, to.pitch);
			p.fallDistance = 0;
			p.playNotifySound(SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1f, 1f);
			BubbleServer.title(p, Component.literal("SWAP!").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD), Component.empty(), 0, 25, 10);
		}
	}

	// ------------------------------------------------------------------ attributes

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, path);
	}

	private static void set(ServerPlayer p, Holder<Attribute> attr, String key, double amount, AttributeModifier.Operation op, boolean on) {
		AttributeInstance inst = p.getAttribute(attr);
		if (inst == null) {
			return;
		}
		ResourceLocation rl = id(key);
		if (!on || amount == 0) {
			inst.removeModifier(rl);
			return;
		}
		AttributeModifier current = inst.getModifier(rl);
		if (current == null || current.amount() != amount || current.operation() != op) {
			inst.addOrUpdateTransientModifier(new AttributeModifier(rl, amount, op));
		}
	}

	/** Puts every run modifier and Eidolon upgrade on this player (and takes off what no longer applies). */
	static void applyAttributes(ServerPlayer p) {
		boolean lowGravity = active.containsKey("low_gravity");
		boolean tiny = active.containsKey("tiny");
		boolean glass = active.containsKey("glass_cannon");
		set(p, Attributes.GRAVITY, "mod_low_gravity", -0.6, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, lowGravity);
		set(p, Attributes.SAFE_FALL_DISTANCE, "mod_low_gravity_fall", 12, AttributeModifier.Operation.ADD_VALUE, lowGravity);
		set(p, Attributes.SCALE, "mod_tiny", -0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, tiny);
		set(p, Attributes.MAX_HEALTH, "mod_glass_cannon", -0.5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, glass); // half health
		set(p, Attributes.ATTACK_DAMAGE, "mod_glass_cannon_dmg", 0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, false); // old 2x, now a damage hook
		// Eidolon upgrades
		int tough = Eidolons.level(p, "tough");
		set(p, Attributes.MAX_HEALTH, "up_tough", 2.0 * tough, AttributeModifier.Operation.ADD_VALUE, tough > 0);
		int lucky = Eidolons.level(p, "lucky");
		set(p, Attributes.LUCK, "up_lucky", 0.5 * lucky, AttributeModifier.Operation.ADD_VALUE, lucky > 0);
		if (p.getHealth() > p.getMaxHealth()) {
			p.setHealth(p.getMaxHealth());
		}
	}

	// ------------------------------------------------------------------ file

	private static void load() {
		active.clear();
		speedrunClaimed = false;
		bountyClaimed = false;
		pendingBlasts.clear();
		pendingLevels.clear();
		try {
			if (Files.exists(file)) {
				JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
				if (o.has("active")) {
					JsonObject a = o.getAsJsonObject("active");
					a.keySet().forEach(k -> active.put(k, a.get(k).getAsLong()));
				}
				speedrunClaimed = o.has("speedrunClaimed") && o.get("speedrunClaimed").getAsBoolean();
				bountyClaimed = o.has("bountyClaimed") && o.get("bountyClaimed").getAsBoolean();
			}
		} catch (Exception e) {
			Bubble.LOG.error("Could not read {}", file, e);
		}
	}

	private static void save() {
		if (file == null) {
			return;
		}
		try {
			JsonObject o = new JsonObject();
			JsonObject a = new JsonObject();
			active.forEach(a::addProperty);
			o.add("active", a);
			o.addProperty("speedrunClaimed", speedrunClaimed);
			o.addProperty("bountyClaimed", bountyClaimed);
			Files.writeString(file, o.toString());
		} catch (Exception e) {
			Bubble.LOG.error("Could not save {}", file, e);
		}
	}
}
