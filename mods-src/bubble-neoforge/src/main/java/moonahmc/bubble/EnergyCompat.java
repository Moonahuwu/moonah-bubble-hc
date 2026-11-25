package moonahmc.bubble;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The shop's Charging tab: fills items for Strange Matter.
 * <ul>
 * <li>Energy (NeoForge's energy capability, "FE"): Mekanism, Oritech, Applied Energistics 2, Cyclic, the backpack
 * battery upgrade and most other tech mods.</li>
 * <li>EMC (ProjectE): Klein Stars, gem armour, rings and amulets that run on EMC (EmcCompat).</li>
 * </ul>
 * Items in your inventory, armour and offhand count, and so do accessories worn in Curios slots (slot numbers from
 * {@link #CURIO_BASE} up). The tab only shows when some installed item can be charged.
 */
final class EnergyCompat {
	static final String FE = "FE";
	static final String EMC = "EMC";
	/** Curios slots are numbered from here (inventory slots are below). */
	static final int CURIO_BASE = 1000;
	private static Boolean available;

	private EnergyCompat() {
	}

	/** True when any registered item holds energy or EMC. Worked out once; says per mod in the log. */
	static boolean available() {
		if (available == null) {
			Map<String, Integer> fe = new TreeMap<>();
			Map<String, Integer> emc = new TreeMap<>();
			boolean pe = ModList.get().isLoaded("projecte");
			for (Item item : BuiltInRegistries.ITEM) {
				String ns = BuiltInRegistries.ITEM.getKey(item).getNamespace();
				try {
					ItemStack s = new ItemStack(item);
					if (s.getCapability(Capabilities.EnergyStorage.ITEM) != null) {
						fe.merge(ns, 1, Integer::sum);
					} else if (pe && EmcCompat.holds(s)) {
						emc.merge(ns, 1, Integer::sum);
					}
				} catch (Throwable ignored) {
					// some items can't be asked without a world around them
				}
			}
			available = !fe.isEmpty() || !emc.isEmpty();
			Bubble.LOG.info("Strange Matter charging: energy items {}, EMC items {}", fe.isEmpty() ? "none" : fe, emc.isEmpty() ? "none" : emc);
		}
		return available;
	}

	/** Somewhere an item can sit: an inventory slot or a Curios slot. */
	private record Place(int slot, Supplier<ItemStack> get, Consumer<ItemStack> set) {
	}

	private static List<Place> places(ServerPlayer p) {
		List<Place> out = new ArrayList<>();
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			int slot = i;
			out.add(new Place(slot, () -> inv.getItem(slot), s -> inv.setItem(slot, s)));
		}
		if (AccessoryCompat.available()) {
			List<AccessoryCompat.Worn> worn = AccessoryCompat.worn(p);
			for (int k = 0; k < worn.size(); k++) {
				AccessoryCompat.Worn w = worn.get(k);
				out.add(new Place(CURIO_BASE + k, w.get(), w.set()));
			}
		}
		return out;
	}

	/** What can be put into one item: its energy or EMC, how much it holds and how to fill it. */
	interface Cell {
		String unit();

		long amount();

		long capacity();

		/** Puts in up to `max`; returns how much went in. */
		long fill(long max);
	}

	private static Cell cell(ItemStack stack) {
		if (stack.isEmpty()) {
			return null;
		}
		IEnergyStorage es = stack.getCapability(Capabilities.EnergyStorage.ITEM);
		if (es != null && es.canReceive() && es.getMaxEnergyStored() > 0) {
			return new Cell() {
				public String unit() {
					return FE;
				}

				public long amount() {
					return es.getEnergyStored();
				}

				public long capacity() {
					return es.getMaxEnergyStored();
				}

				public long fill(long max) {
					long total = 0;
					// Items only take so much per insert (it's meant to be per tick), so keep going until it's full.
					for (int i = 0; i < 20000 && total < max; i++) {
						IEnergyStorage now = stack.getCapability(Capabilities.EnergyStorage.ITEM);
						if (now == null) {
							break;
						}
						int in = now.receiveEnergy((int) Math.min(Integer.MAX_VALUE, max - total), false);
						if (in <= 0) {
							break;
						}
						total += in;
					}
					return total;
				}
			};
		}
		if (ModList.get().isLoaded("projecte")) {
			return EmcCompat.cell(stack);
		}
		return null;
	}

	static List<ShopPayloads.Chargeable> list(ServerPlayer p, int feRate, int emcRate) {
		List<ShopPayloads.Chargeable> out = new ArrayList<>();
		for (Place pl : places(p)) {
			ItemStack stack = pl.get().get();
			Cell c = cell(stack);
			if (c == null) {
				continue;
			}
			int rate = c.unit().equals(EMC) ? emcRate : feRate;
			long missing = Math.max(0, c.capacity() - c.amount());
			out.add(new ShopPayloads.Chargeable(pl.slot(), stack.copy(), c.amount(), c.capacity(),
					(int) Math.min(Integer.MAX_VALUE, (missing + rate - 1) / rate), c.unit()));
		}
		return out;
	}

	/** Puts up to `max` (of that item's unit) into the item at `slot`; returns how much went in. */
	static long charge(ServerPlayer p, int slot, long max) {
		for (Place pl : places(p)) {
			if (pl.slot() != slot) {
				continue;
			}
			ItemStack stack = pl.get().get();
			Cell c = cell(stack);
			if (c == null) {
				return 0;
			}
			long in = c.fill(max);
			if (in > 0) {
				pl.set().accept(stack); // tell the inventory / Curios it changed
				p.getInventory().setChanged();
			}
			return in;
		}
		return 0;
	}
}
