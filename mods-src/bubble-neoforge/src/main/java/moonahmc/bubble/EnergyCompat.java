package moonahmc.bubble;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.ArrayList;
import java.util.List;

/**
 * Charging items through NeoForge's energy capability (Forge Energy), which Oritech, Mekanism, Powah, the backpack
 * battery upgrade and most other tech mods use. The Charging tab only shows when some installed item can hold energy.
 */
final class EnergyCompat {
	private static Boolean available;

	private EnergyCompat() {
	}

	/** True when any registered item has an energy storage. Worked out once, the first time it's asked. */
	static boolean available() {
		if (available == null) {
			boolean any = false;
			for (Item item : BuiltInRegistries.ITEM) {
				try {
					if (new ItemStack(item).getCapability(Capabilities.EnergyStorage.ITEM) != null) {
						any = true;
						break;
					}
				} catch (RuntimeException ignored) {
					// some items can't be asked without a world around them
				}
			}
			available = any;
			Bubble.LOG.info("Strange Matter charging: {}", any ? "energy items found" : "no energy items installed, tab hidden");
		}
		return available;
	}

	private static IEnergyStorage storage(ServerPlayer p, int slot) {
		ItemStack stack = p.getInventory().getItem(slot);
		if (stack.isEmpty()) {
			return null;
		}
		IEnergyStorage es = stack.getCapability(Capabilities.EnergyStorage.ITEM);
		return es != null && es.canReceive() && es.getMaxEnergyStored() > 0 ? es : null;
	}

	static List<ShopPayloads.Chargeable> list(ServerPlayer p, int rate) {
		List<ShopPayloads.Chargeable> out = new ArrayList<>();
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			IEnergyStorage es = storage(p, i);
			if (es != null) {
				long missing = (long) es.getMaxEnergyStored() - es.getEnergyStored();
				out.add(new ShopPayloads.Chargeable(i, p.getInventory().getItem(i).copy(), es.getEnergyStored(), es.getMaxEnergyStored(),
						(int) Math.min(Integer.MAX_VALUE, (missing + rate - 1) / rate)));
			}
		}
		return out;
	}

	/** Puts up to `max` energy into the item in `slot`; returns how much went in. */
	static long charge(ServerPlayer p, int slot, long max) {
		long total = 0;
		// Items only take so much per insert (it's meant to be per tick), so keep going until it's full.
		for (int i = 0; i < 20000 && total < max; i++) {
			IEnergyStorage es = storage(p, slot);
			if (es == null) {
				break;
			}
			int in = es.receiveEnergy((int) Math.min(Integer.MAX_VALUE, max - total), false);
			if (in <= 0) {
				break;
			}
			total += in;
		}
		if (total > 0) {
			p.getInventory().setChanged();
		}
		return total;
	}
}
