package moonahmc.bubble;

import moze_intel.projecte.api.capabilities.PECapabilities;
import moze_intel.projecte.api.capabilities.block_entity.IEmcStorage;
import moze_intel.projecte.api.capabilities.item.IItemEmcHolder;
import net.minecraft.world.item.ItemStack;

/**
 * ProjectE items that hold EMC (Klein Stars, gem armour, EMC-powered rings and amulets), for the shop's Charging tab.
 * Only loaded when ProjectE is installed. EMC can't be turned back into items here (transmutation is off), so
 * charging only powers the gear.
 */
final class EmcCompat {
	private EmcCompat() {
	}

	static boolean holds(ItemStack s) {
		IItemEmcHolder h = s.getCapability(PECapabilities.EMC_HOLDER_ITEM_CAPABILITY);
		return h != null && h.getMaximumEmc(s) > 0;
	}

	static EnergyCompat.Cell cell(ItemStack stack) {
		IItemEmcHolder h = stack.getCapability(PECapabilities.EMC_HOLDER_ITEM_CAPABILITY);
		if (h == null || h.getMaximumEmc(stack) <= 0) {
			return null;
		}
		return new EnergyCompat.Cell() {
			public String unit() {
				return EnergyCompat.EMC;
			}

			public long amount() {
				return h.getStoredEmc(stack);
			}

			public long capacity() {
				return h.getMaximumEmc(stack);
			}

			public long fill(long max) {
				return Math.max(0, h.insertEmc(stack, max, IEmcStorage.EmcAction.EXECUTE));
			}
		};
	}
}
