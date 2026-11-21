package moonahmc.bubble;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;

/**
 * Extra loot in every chest loot table (vanilla, structure mods, and Lootr chests, which roll the same tables): now and
 * then a Wheel Spin, and very rarely a gun with ammo. The chances are in config/bubble-cases.json under "extras".
 */
public final class ChestExtras extends LootModifier {
	public static final MapCodec<ChestExtras> CODEC = RecordCodecBuilder.mapCodec(inst -> codecStart(inst).apply(inst, ChestExtras::new));

	public ChestExtras(LootItemCondition[] conditions) {
		super(conditions);
	}

	@Override
	protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext ctx) {
		var id = ctx.getQueriedLootTableId();
		if (id == null || !id.getPath().startsWith("chests/")) {
			return loot;
		}
		var r = ctx.getRandom();
		if (r.nextDouble() < Gamble.extra("chestWheelSpin", 0.03)) {
			loot.add(new ItemStack(ModContent.WHEEL_SPIN.get()));
		}
		if (Guns.available() && r.nextDouble() < Gamble.extra("chestGun", 0.006)) {
			Guns.Gun g = Guns.random(r);
			loot.add(Guns.gun(g));
			loot.add(Guns.ammo(g));
		}
		return loot;
	}

	@Override
	public MapCodec<? extends IGlobalLootModifier> codec() {
		return CODEC;
	}
}
