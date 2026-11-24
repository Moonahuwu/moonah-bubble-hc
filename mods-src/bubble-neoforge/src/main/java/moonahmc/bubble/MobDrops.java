package moonahmc.bubble;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Cases, Wheel Spins and Strange Matter from mobs a player kills. Any hostile mob has a small chance of dropping a
 * random case; Bubble's own mobs (and the Warden) have their own chances, pay Strange Matter by how hard they are, and
 * the Warden and the Evil Watcher always drop a case or a spin. All of it is in config/bubble-cases.json, "mobDrops".
 */
final class MobDrops {
	private MobDrops() {
	}

	/** The defaults, added to the cases file once (see Gamble.migrate). */
	static JsonObject defaults() {
		return JsonParser.parseString("""
				{
				  "anyMobCase": 0.002,
				  "mobs": {
				    "bubble:swarmling":        {"matter": 0,   "case": 0.0015, "spin": 0.001},
				    "bubble:rainbow_creeper":  {"matter": 1,   "case": 0.004,  "spin": 0.003},
				    "bubble:gatling_skeleton": {"matter": 12,  "case": 0.05,   "spin": 0.02},
				    "bubble:jockey_jockey":    {"matter": 15,  "case": 0.06,   "spin": 0.025},
				    "bubble:nuke_creeper":     {"matter": 30,  "case": 0.12,   "spin": 0.05},
				    "bubble:swarm_zombie":     {"matter": 40,  "case": 0.2,    "spin": 0.05},
				    "bubble:watcher":          {"matter": 40,  "case": 0.2,    "spin": 0.05},
				    "bubble:evil_watcher":     {"matter": 120, "guaranteed": true},
				    "minecraft:warden":        {"matter": 150, "guaranteed": true}
				  }
				}""").getAsJsonObject();
	}

	static void init() {
		NeoForge.EVENT_BUS.addListener((LivingDropsEvent e) -> {
			LivingEntity dead = e.getEntity();
			if (dead.level().isClientSide || !(dead instanceof Mob) || !(e.getSource().getEntity() instanceof ServerPlayer killer)) {
				return;
			}
			if (Lobby.active() || !BubbleServer.playing()) {
				return;
			}
			JsonObject cfg = Gamble.mobDrops();
			if (cfg == null) {
				return;
			}
			String id = BuiltInRegistries.ENTITY_TYPE.getKey(dead.getType()).toString();
			JsonObject mobs = cfg.has("mobs") ? cfg.getAsJsonObject("mobs") : new JsonObject();
			Component name = dead.getType().getDescription();
			boolean elite = net.neoforged.fml.ModList.get().isLoaded("apotheosis") && ApothElites.isElite(dead);
			Result res = roll(cfg, id, dead instanceof Enemy, dead.getRandom(), elite);
			if (res.matter > 0) {
				Matter.earn(killer, res.matter);
				killer.displayClientMessage(Component.literal("+" + Matter.format(res.matter) + " Strange Matter (").withStyle(ChatFormatting.LIGHT_PURPLE)
						.append(name.copy()).append(")"), true);
			}
			List<ItemStack> drops = res.drops;
			drops.removeIf(ItemStack::isEmpty);
			for (ItemStack s : drops) {
				ItemEntity item = new ItemEntity(dead.level(), dead.getX(), dead.getY() + 0.5, dead.getZ(), s);
				item.setGlowingTag(true);
				e.getDrops().add(item);
				boolean big = elite || mobs.has(id) && (mobs.getAsJsonObject(id).has("guaranteed") || num(mobs.getAsJsonObject(id), "matter", 0) >= 30);
				Component line = Component.literal("🎁 ").withStyle(ChatFormatting.GOLD).append(killer.getDisplayName())
						.append(Component.literal(" got ").withStyle(ChatFormatting.GRAY)).append(s.getHoverName())
						.append(Component.literal(" from ").withStyle(ChatFormatting.GRAY)).append(name.copy().withStyle(ChatFormatting.WHITE));
				if (big) {
					killer.server.getPlayerList().broadcastSystemMessage(line, false);
				} else {
					killer.sendSystemMessage(line);
				}
				killer.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5f, 1.6f);
			}
		});
	}

	record Result(double matter, List<ItemStack> drops) {
	}

	/**
	 * What one kill of this mob gives. An elite (Apotheosis) pays "eliteBonus" times as much (3 by default) with that
	 * many times the case and spin chances; an elite of any other mob gets a case at the "eliteCase" chance (25%).
	 */
	static Result roll(JsonObject cfg, String id, boolean enemy, RandomSource r, boolean elite) {
		JsonObject mobs = cfg.has("mobs") ? cfg.getAsJsonObject("mobs") : new JsonObject();
		List<ItemStack> drops = new ArrayList<>();
		double matter = 0;
		double bonus = elite ? Math.max(1, num(cfg, "eliteBonus", 3)) : 1;
		if (mobs.has(id)) {
			JsonObject m = mobs.getAsJsonObject(id);
			matter = num(m, "matter", 0) * bonus;
			if (m.has("guaranteed") && m.get("guaranteed").getAsBoolean()) {
				// a case or a spin, every time (a spin 1 time in 4)
				drops.add(r.nextInt(4) == 0 ? spin() : randomCase(r));
			} else {
				if (r.nextDouble() < num(m, "case", 0) * bonus) {
					drops.add(randomCase(r));
				}
				if (r.nextDouble() < num(m, "spin", 0) * bonus) {
					drops.add(spin());
				}
			}
			if (elite && m.has("guaranteed") && m.get("guaranteed").getAsBoolean()) {
				drops.add(randomCase(r)); // an elite Evil Watcher or Warden: a case on top
			}
		} else if (elite && r.nextDouble() < num(cfg, "eliteCase", 0.25)) {
			drops.add(randomCase(r));
		} else if (enemy && r.nextDouble() < num(cfg, "anyMobCase", 0)) {
			drops.add(randomCase(r));
		}
		drops.removeIf(ItemStack::isEmpty);
		return new Result(matter, drops);
	}

	/** /bubble odds mob <entity> [kills]: what that many kills would give, without killing anything. */
	static String simulate(String id, int kills, RandomSource r, boolean elite) {
		JsonObject cfg = Gamble.mobDrops();
		if (cfg == null) {
			return "No mobDrops in config/bubble-cases.json.";
		}
		boolean enemy = BuiltInRegistries.ENTITY_TYPE.getOptional(net.minecraft.resources.ResourceLocation.tryParse(id))
				.map(t -> t.getCategory() == net.minecraft.world.entity.MobCategory.MONSTER).orElse(true);
		int cases = 0, spins = 0;
		double matter = 0;
		for (int i = 0; i < kills; i++) {
			Result res = roll(cfg, id, enemy, r, elite);
			matter += res.matter;
			for (ItemStack s : res.drops) {
				if (s.is(ModContent.WHEEL_SPIN.get())) {
					spins++;
				} else {
					cases++;
				}
			}
		}
		return kills + " kills of " + (elite ? "elite " : "") + id + ": " + cases + " cases, " + spins + " spins, " + Matter.format(matter) + " Strange Matter";
	}

	private static double num(JsonObject o, String key, double def) {
		JsonElement v = o.get(key);
		return v != null && v.isJsonPrimitive() ? v.getAsDouble() : def;
	}

	private static ItemStack spin() {
		return new ItemStack(ModContent.WHEEL_SPIN.get());
	}

	/** One of the cases in the cases file, picked at random. */
	private static ItemStack randomCase(RandomSource r) {
		List<String> ids = new ArrayList<>(Gamble.ids());
		if (ids.isEmpty()) {
			return ItemStack.EMPTY;
		}
		return CaseItem.of(ids.get(r.nextInt(ids.size())), 1);
	}
}
