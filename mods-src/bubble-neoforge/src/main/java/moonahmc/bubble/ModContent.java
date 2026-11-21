package moonahmc.bubble;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Bubble's own mobs: the rainbow creeper, the nuke creeper and the gatling skeleton, plus their spawn eggs. */
public final class ModContent {
	private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, Bubble.MOD_ID);
	private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, Bubble.MOD_ID);
	/** A case to open (quest rewards, /bubble givecase). Which case is in its custom data. */
	public static final DeferredHolder<Item, CaseItem> CASE = ITEMS.register("case",
			() -> new CaseItem(new Item.Properties().stacksTo(16).rarity(net.minecraft.world.item.Rarity.RARE)));
	/** Spins the mid-run wheel (found in cases and chests). */
	public static final DeferredHolder<Item, WheelSpinItem> WHEEL_SPIN = ITEMS.register("wheel_spin",
			() -> new WheelSpinItem(new Item.Properties().stacksTo(16).rarity(net.minecraft.world.item.Rarity.EPIC)));
	private static final DeferredRegister<com.mojang.serialization.MapCodec<? extends net.neoforged.neoforge.common.loot.IGlobalLootModifier>> LOOT_MODIFIERS =
			DeferredRegister.create(net.neoforged.neoforge.registries.NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, Bubble.MOD_ID);
	static {
		LOOT_MODIFIERS.register("chest_extras", () -> ChestExtras.CODEC);
	}

	public static final DeferredHolder<EntityType<?>, EntityType<RainbowCreeper>> RAINBOW_CREEPER = ENTITIES.register("rainbow_creeper",
			() -> EntityType.Builder.of(RainbowCreeper::new, MobCategory.MONSTER).sized(0.6f, 1.7f).clientTrackingRange(8).build("bubble:rainbow_creeper"));
	public static final DeferredHolder<EntityType<?>, EntityType<NukeCreeper>> NUKE_CREEPER = ENTITIES.register("nuke_creeper",
			() -> EntityType.Builder.of(NukeCreeper::new, MobCategory.MONSTER).sized(0.84f, 2.38f).clientTrackingRange(10).build("bubble:nuke_creeper"));

	public static final DeferredHolder<EntityType<?>, EntityType<GatlingSkeleton>> GATLING_SKELETON = ENTITIES.register("gatling_skeleton",
			() -> EntityType.Builder.of(GatlingSkeleton::new, MobCategory.MONSTER).sized(0.6f, 1.99f).eyeHeight(1.74f).clientTrackingRange(8).build("bubble:gatling_skeleton"));
	public static final DeferredHolder<EntityType<?>, EntityType<GatlingArrow>> GATLING_ARROW = ENTITIES.register("gatling_arrow",
			() -> EntityType.Builder.<GatlingArrow>of(GatlingArrow::new, MobCategory.MISC).sized(0.5f, 0.5f).clientTrackingRange(4).updateInterval(20)
					.build("bubble:gatling_arrow"));

	public static final DeferredHolder<EntityType<?>, EntityType<Watcher>> WATCHER = ENTITIES.register("watcher",
			() -> EntityType.Builder.of(Watcher::new, MobCategory.MONSTER).sized(0.6f, 2.9f).eyeHeight(2.55f).clientTrackingRange(8).build("bubble:watcher"));
	public static final DeferredHolder<EntityType<?>, EntityType<EvilWatcher>> EVIL_WATCHER = ENTITIES.register("evil_watcher",
			() -> EntityType.Builder.of(EvilWatcher::new, MobCategory.MONSTER).sized(0.6f, 2.9f).eyeHeight(2.55f).clientTrackingRange(10).build("bubble:evil_watcher"));

	public static final DeferredHolder<EntityType<?>, EntityType<JockeyJockey>> JOCKEY_JOCKEY = ENTITIES.register("jockey_jockey",
			() -> EntityType.Builder.of(JockeyJockey::new, MobCategory.MONSTER).sized(1.4f, 0.9f).clientTrackingRange(8).build("bubble:jockey_jockey"));
	public static final DeferredHolder<EntityType<?>, EntityType<SwarmZombie>> SWARM_ZOMBIE = ENTITIES.register("swarm_zombie",
			() -> EntityType.Builder.of(SwarmZombie::new, MobCategory.MONSTER).sized(0.6f, 1.95f).eyeHeight(1.74f).clientTrackingRange(8).build("bubble:swarm_zombie"));
	public static final DeferredHolder<EntityType<?>, EntityType<Swarmling>> SWARMLING = ENTITIES.register("swarmling",
			() -> EntityType.Builder.of(Swarmling::new, MobCategory.MONSTER).sized(0.6f, 1.95f).eyeHeight(1.74f).clientTrackingRange(6).build("bubble:swarmling"));

	static {
		ITEMS.register("jockey_jockey_spawn_egg", () -> new DeferredSpawnEggItem(JOCKEY_JOCKEY, 0x342D27, 0x0C424E, new Item.Properties()));
		ITEMS.register("swarm_zombie_spawn_egg", () -> new DeferredSpawnEggItem(SWARM_ZOMBIE, 0x00AFAF, 0x799C65, new Item.Properties()));
		ITEMS.register("watcher_spawn_egg", () -> new DeferredSpawnEggItem(WATCHER, 0x161616, 0xFF2020, new Item.Properties()));
		ITEMS.register("evil_watcher_spawn_egg", () -> new DeferredSpawnEggItem(EVIL_WATCHER, 0x161616, 0xFF4FD8, new Item.Properties()));
		ITEMS.register("gatling_skeleton_spawn_egg", () -> new DeferredSpawnEggItem(GATLING_SKELETON, 0xC1C1C1, 0x8A1C1C, new Item.Properties()));
		ITEMS.register("rainbow_creeper_spawn_egg", () -> new DeferredSpawnEggItem(RAINBOW_CREEPER, 0xFF4FD8, 0x52E8FF, new Item.Properties()));
		ITEMS.register("nuke_creeper_spawn_egg", () -> new DeferredSpawnEggItem(NUKE_CREEPER, 0x2F3B1F, 0xF5D90A, new Item.Properties()));
	}

	private ModContent() {
	}

	static void register(IEventBus modBus) {
		ENTITIES.register(modBus);
		ITEMS.register(modBus);
		LOOT_MODIFIERS.register(modBus);
		modBus.addListener((EntityAttributeCreationEvent e) -> {
			e.put(GATLING_SKELETON.get(), net.minecraft.world.entity.monster.AbstractSkeleton.createAttributes().build());
			e.put(WATCHER.get(), net.minecraft.world.entity.monster.EnderMan.createAttributes().build());
			e.put(EVIL_WATCHER.get(), net.minecraft.world.entity.monster.EnderMan.createAttributes().add(Attributes.MAX_HEALTH, 60).build());
			e.put(JOCKEY_JOCKEY.get(), net.minecraft.world.entity.monster.Spider.createAttributes().add(Attributes.MAX_HEALTH, 24).build());
			e.put(SWARM_ZOMBIE.get(), net.minecraft.world.entity.monster.Zombie.createAttributes().build());
			e.put(SWARMLING.get(), net.minecraft.world.entity.monster.Zombie.createAttributes().add(Attributes.MAX_HEALTH, 1)
					.add(Attributes.SCALE, 0.33).add(Attributes.MOVEMENT_SPEED, 0.3).add(Attributes.ATTACK_DAMAGE, 1).build());
			e.put(RAINBOW_CREEPER.get(), Creeper.createAttributes().add(Attributes.MOVEMENT_SPEED, 0.25).add(Attributes.FOLLOW_RANGE, 128).build());
			e.put(NUKE_CREEPER.get(), Creeper.createAttributes().add(Attributes.MOVEMENT_SPEED, 0.25 * NukeCreeper.SPEED)
					.add(Attributes.MAX_HEALTH, 60).add(Attributes.KNOCKBACK_RESISTANCE, 0.9).build());
		});
		modBus.addListener((BuildCreativeModeTabContentsEvent e) -> {
			if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
				e.accept(WHEEL_SPIN.get());
			}
			if (e.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
				ITEMS.getEntries().stream().filter(i -> i.get() instanceof DeferredSpawnEggItem).forEach(i -> e.accept(i.get()));
			}
		});
	}
}
