package moonahmc.bubble;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import java.util.List;
import java.util.Optional;

/**
 * Guns from TACZ (Timeless and Classics Zero): super rare in cases, chests (Lootr ones too) and on mobs. A gun comes
 * with a full magazine and a stack of its own ammo. Rocket and grenade launchers are left out (they blow up the
 * landscape). Nothing here does anything without TACZ installed.
 */
public final class Guns {
	private static final ResourceLocation GUN_ITEM = ResourceLocation.fromNamespaceAndPath("tacz", "modern_kinetic_gun");
	private static final ResourceLocation AMMO_ITEM = ResourceLocation.fromNamespaceAndPath("tacz", "ammo");
	static final String GUNNER_TAG = "bubble_gunner";

	record Gun(String id, String ammo, int mag, String mode) {
	}

	static final List<Gun> GUNS = List.of(
			new Gun("ak47", "762x39", 30, "AUTO"), new Gun("ak12", "545x39", 30, "AUTO"), new Gun("m4a1", "556x45", 30, "AUTO"),
			new Gun("hk416d", "556x45", 30, "AUTO"), new Gun("scar_l", "556x45", 30, "AUTO"), new Gun("aug", "556x45", 30, "AUTO"),
			new Gun("g36k", "556x45", 30, "AUTO"), new Gun("scar_h", "308", 20, "SEMI"), new Gun("fn_fal", "308", 20, "SEMI"),
			new Gun("type_81", "762x39", 30, "AUTO"), new Gun("qbz_95", "58x42", 30, "AUTO"), new Gun("mcx_spear_lt", "300blk", 30, "AUTO"),
			new Gun("hk_mp5a5", "9mm", 30, "AUTO"), new Gun("p90", "57x28", 50, "AUTO"), new Gun("vector45", "45acp", 20, "AUTO"),
			new Gun("ump45", "45acp", 25, "AUTO"), new Gun("uzi", "9mm", 20, "AUTO"), new Gun("hk_mp7a1", "46x30", 20, "AUTO"),
			new Gun("glock_17", "9mm", 17, "SEMI"), new Gun("m1911", "45acp", 7, "SEMI"), new Gun("deagle", "50ae", 7, "SEMI"),
			new Gun("deagle_golden", "357mag", 9, "SEMI"), new Gun("rhino357", "357mag", 6, "SEMI"), new Gun("taurus500", "500mag", 5, "SEMI"),
			new Gun("m870", "12g", 5, "SEMI"), new Gun("spas_12", "12g", 5, "SEMI"), new Gun("aa12", "12g", 8, "AUTO"),
			new Gun("db_short", "12g", 2, "SEMI"), new Gun("m1014", "12g", 6, "SEMI"), new Gun("svd", "762x54", 10, "SEMI"),
			new Gun("ai_awp", "338", 5, "SEMI"), new Gun("m700", "30_06", 5, "SEMI"), new Gun("kar98", "792x57", 4, "SEMI"),
			new Gun("m107", "50bmg", 10, "SEMI"), new Gun("mk14", "308", 10, "SEMI"), new Gun("sks_tactical", "762x39", 10, "SEMI"),
			new Gun("m249", "556x45", 75, "AUTO"), new Gun("rpk", "762x39", 40, "AUTO"), new Gun("pkm", "762x54", 75, "AUTO"));

	private Guns() {
	}

	static boolean available() {
		return ModList.get().isLoaded("tacz") && BuiltInRegistries.ITEM.containsKey(GUN_ITEM);
	}

	static Optional<Gun> find(String id) {
		String plain = id.startsWith("tacz:") ? id.substring(5) : id;
		return GUNS.stream().filter(g -> g.id.equals(plain)).findFirst();
	}

	static Gun random(RandomSource r) {
		return GUNS.get(r.nextInt(GUNS.size()));
	}

	/** The gun, loaded. */
	static ItemStack gun(Gun g) {
		Item item = BuiltInRegistries.ITEM.get(GUN_ITEM);
		ItemStack stack = new ItemStack(item);
		CompoundTag tag = new CompoundTag();
		tag.putString("GunId", "tacz:" + g.id);
		tag.putString("GunFireMode", g.mode);
		tag.putInt("GunCurrentAmmoCount", g.mag);
		tag.putBoolean("HasBulletInBarrel", true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return stack;
	}

	/** A few magazines' worth of the gun's ammo. */
	static ItemStack ammo(Gun g) {
		Item item = BuiltInRegistries.ITEM.get(AMMO_ITEM);
		int count = Math.max(10, Math.min(60, g.mag * 3));
		ItemStack stack = new ItemStack(item, Math.min(count, item.getDefaultMaxStackSize()));
		CompoundTag tag = new CompoundTag();
		tag.putString("AmmoId", "tacz:" + g.ammo);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return stack;
	}

	/** For case tiles: just the gun to look at (the command gives the real one with ammo). */
	static com.google.gson.JsonObject caseDrop(String id) {
		com.google.gson.JsonObject custom = new com.google.gson.JsonObject();
		custom.addProperty("GunId", "tacz:" + id);
		com.google.gson.JsonObject components = new com.google.gson.JsonObject();
		components.add("minecraft:custom_data", custom);
		com.google.gson.JsonObject stack = new com.google.gson.JsonObject();
		stack.addProperty("id", GUN_ITEM.toString());
		stack.addProperty("count", 1);
		stack.add("components", components);
		com.google.gson.JsonObject drop = new com.google.gson.JsonObject();
		drop.add("stack", stack);
		drop.addProperty("command", "bubble gun {player} " + id);
		return drop;
	}

	static void give(ServerPlayer p, Gun g) {
		ItemStack gun = gun(g);
		p.sendSystemMessage(Component.literal("  + ").withStyle(ChatFormatting.GRAY).append(gun.getHoverName())
				.append(Component.literal(" (with ammo)").withStyle(ChatFormatting.GRAY)));
		for (ItemStack s : new ItemStack[] {gun, ammo(g)}) {
			if (!p.getInventory().add(s)) {
				p.drop(s, false);
			}
		}
		p.containerMenu.broadcastChanges();
	}

	static void init() {
		if (!ModList.get().isLoaded("tacz")) {
			return;
		}
		var bus = NeoForge.EVENT_BUS;
		// Now and then a zombie, skeleton, piglin or illager spawns holding a gun. They can't fire it, but you get it
		// (and some ammo) when you kill them.
		bus.addListener((EntityJoinLevelEvent e) -> {
			if (e.getLevel().isClientSide || e.loadedFromDisk() || !(e.getEntity() instanceof Mob mob)) {
				return;
			}
			if (!(mob instanceof Zombie || mob instanceof AbstractSkeleton || mob instanceof AbstractPiglin || mob instanceof AbstractIllager)) {
				return;
			}
			if (mob.getTags().contains(GUNNER_TAG) || mob.getRandom().nextFloat() >= Gamble.extra("mobGunChance", 0.002)) {
				return;
			}
			Gun g = random(mob.getRandom());
			mob.addTag(GUNNER_TAG);
			mob.addTag(GUNNER_TAG + ":" + g.id);
			mob.setItemSlot(EquipmentSlot.MAINHAND, gun(g));
			mob.setDropChance(EquipmentSlot.MAINHAND, 2.0f); // always drops, undamaged
			mob.setPersistenceRequired();
		});
		bus.addListener((LivingDropsEvent e) -> {
			if (e.getEntity().level().isClientSide || !e.getEntity().getTags().contains(GUNNER_TAG)) {
				return;
			}
			e.getEntity().getTags().stream().filter(t -> t.startsWith(GUNNER_TAG + ":")).findFirst()
					.flatMap(t -> find(t.substring(GUNNER_TAG.length() + 1))).ifPresent(g -> {
						var at = e.getEntity().position();
						e.getDrops().add(new ItemEntity(e.getEntity().level(), at.x, at.y + 0.5, at.z, ammo(g)));
					});
		});
	}
}
