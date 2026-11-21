package moonahmc.bubble;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

/**
 * Curios accessories (artifacts, relics, rings...). Only touched when Curios is installed.
 * - A run-ending death saves the dead player's accessories with their inventory for "Another life" (they used to drop).
 * - Right-click an accessory to put it straight on (into a free slot, or swapping with the one that's there).
 */
public final class AccessoryCompat {
	private AccessoryCompat() {
	}

	public static boolean available() {
		return ModList.get().isLoaded("curios");
	}

	/** Takes every equipped accessory (and cosmetic one) off the player and returns them, or null if they had none. */
	static CompoundTag capture(ServerPlayer p) {
		if (!available()) {
			return null;
		}
		ListTag list = new ListTag();
		CuriosApi.getCuriosInventory(p).ifPresent(handler -> handler.getCurios().forEach((id, slots) -> {
			take(p, list, id, slots.getStacks(), false);
			take(p, list, id, slots.getCosmeticStacks(), true);
		}));
		if (list.isEmpty()) {
			return null;
		}
		CompoundTag tag = new CompoundTag();
		tag.put("curios", list);
		return tag;
	}

	private static void take(ServerPlayer p, ListTag list, String id, IDynamicStackHandler stacks, boolean cosmetic) {
		for (int i = 0; i < stacks.getSlots(); i++) {
			ItemStack s = stacks.getStackInSlot(i);
			if (s.isEmpty()) {
				continue;
			}
			CompoundTag e = new CompoundTag();
			e.putString("id", id);
			e.putInt("slot", i);
			e.putBoolean("cosmetic", cosmetic);
			e.put("item", s.save(p.registryAccess()));
			list.add(e);
			stacks.setStackInSlot(i, ItemStack.EMPTY);
		}
	}

	/** Puts captured accessories back on (anything that no longer fits goes into the inventory). */
	static void restore(ServerPlayer p, CompoundTag tag) {
		if (tag == null || !available()) {
			return;
		}
		var handler = CuriosApi.getCuriosInventory(p).orElse(null);
		for (Tag t : tag.getList("curios", Tag.TAG_COMPOUND)) {
			CompoundTag e = (CompoundTag) t;
			ItemStack s = ItemStack.parseOptional(p.registryAccess(), e.getCompound("item"));
			if (s.isEmpty()) {
				continue;
			}
			boolean placed = false;
			if (handler != null) {
				var slots = handler.getStacksHandler(e.getString("id")).orElse(null);
				if (slots != null) {
					IDynamicStackHandler stacks = e.getBoolean("cosmetic") ? slots.getCosmeticStacks() : slots.getStacks();
					int i = e.getInt("slot");
					if (i < stacks.getSlots() && stacks.getStackInSlot(i).isEmpty()) {
						stacks.setStackInSlot(i, s);
						placed = true;
					}
				}
			}
			if (!placed && !p.getInventory().add(s)) {
				p.drop(s, false);
			}
		}
	}

	static void init() {
		if (!available()) {
			return;
		}
		NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickItem e) -> {
			Player player = e.getEntity();
			ItemStack stack = e.getItemStack();
			if (stack.isEmpty() || stack.has(DataComponents.FOOD) || player.isSpectator() || CuriosApi.getItemStackSlots(stack, player).isEmpty()) {
				return;
			}
			e.setCanceled(true);
			e.setCancellationResult(InteractionResult.SUCCESS);
			if (player instanceof ServerPlayer sp) {
				equip(sp, e.getHand());
			}
		});
	}

	/** Right-click equip: the first free slot that takes it, otherwise swap with the first one that does. */
	private static void equip(ServerPlayer p, InteractionHand hand) {
		ItemStack stack = p.getItemInHand(hand);
		RelicMax.maximizeHeld(p, hand); // fully level it on the way on
		stack = p.getItemInHand(hand);
		var handler = CuriosApi.getCuriosInventory(p).orElse(null);
		if (handler == null) {
			return;
		}
		var valid = CuriosApi.getItemStackSlots(stack, p).keySet();
		String swapId = null;
		int swapSlot = -1;
		for (String id : valid) {
			var slots = handler.getStacksHandler(id).orElse(null);
			if (slots == null) {
				continue;
			}
			IDynamicStackHandler stacks = slots.getStacks();
			for (int i = 0; i < stacks.getSlots(); i++) {
				SlotContext ctx = new SlotContext(id, p, i, false, true);
				if (!CuriosApi.isStackValid(ctx, stack)) {
					continue;
				}
				final ItemStack check = stack;
				boolean canEquip = CuriosApi.getCurio(check).map(c -> c.canEquip(ctx)).orElse(true);
				if (!canEquip) {
					continue;
				}
				if (stacks.getStackInSlot(i).isEmpty()) {
					stacks.setStackInSlot(i, stack.split(1));
					equipped(p);
					return;
				}
				if (swapId == null) {
					swapId = id;
					swapSlot = i;
				}
			}
		}
		if (swapId != null) {
			IDynamicStackHandler stacks = handler.getStacksHandler(swapId).get().getStacks();
			ItemStack old = stacks.getStackInSlot(swapSlot).copy();
			stacks.setStackInSlot(swapSlot, stack.split(1));
			if (p.getItemInHand(hand).isEmpty()) {
				p.setItemInHand(hand, old);
			} else if (!p.getInventory().add(old)) {
				p.drop(old, false);
			}
			equipped(p);
		}
	}

	private static void equipped(ServerPlayer p) {
		p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ARMOR_EQUIP_GENERIC.value(), SoundSource.PLAYERS, 1f, 1f);
		p.containerMenu.broadcastChanges();
	}

	/** For RelicMax: every equipped accessory stack, as (stacks handler, slot) pairs to look at. */
	static void forEachEquipped(ServerPlayer p, java.util.function.BiConsumer<IDynamicStackHandler, Integer> each) {
		if (!available()) {
			return;
		}
		CuriosApi.getCuriosInventory(p).ifPresent(handler -> handler.getCurios().values().forEach(slots -> {
			IDynamicStackHandler stacks = slots.getStacks();
			for (int i = 0; i < stacks.getSlots(); i++) {
				each.accept(stacks, i);
			}
		}));
	}
}
