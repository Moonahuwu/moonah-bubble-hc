package moonahmc.bubble;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Artifacts (Reliquified Artifacts) and other Relics come fully levelled: max rank and level, every ability unlocked,
 * researched and maxed, best stat quality. Done with Relics' own "/relics relic maximize" on the item, which works on
 * the held item, so the relic is put in the player's hand for that instant. Every couple of seconds Bubble looks
 * through each player's inventory and accessory slots for relics it hasn't done yet (they get a "bubble_maxed" mark).
 */
public final class RelicMax {
	private static final String MARK = "bubble_maxed";
	private static Class<?> relicClass;

	private RelicMax() {
	}

	static void init() {
		if (!ModList.get().isLoaded("relics")) {
			return;
		}
		try {
			relicClass = Class.forName("it.hurts.sskirillss.relics.api.relics.IRelicItem");
		} catch (ClassNotFoundException e) {
			Bubble.LOG.warn("Relics is installed but its API wasn't found; relics won't be levelled", e);
			return;
		}
		NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
			if (e.getServer().getTickCount() % 40 != 0) {
				return;
			}
			for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) {
				if (!p.isSpectator() && p.isAlive()) {
					scan(p);
				}
			}
		});
	}

	private static boolean needsMax(ItemStack s) {
		if (relicClass == null || s.isEmpty() || !relicClass.isInstance(s.getItem())) {
			return false;
		}
		CustomData data = s.get(DataComponents.CUSTOM_DATA);
		return data == null || !data.copyTag().getBoolean(MARK);
	}

	private static void scan(ServerPlayer p) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (needsMax(s)) {
				inv.setItem(i, maximize(p, s));
			}
		}
		AccessoryCompat.forEachEquipped(p, (stacks, i) -> {
			ItemStack s = stacks.getStackInSlot(i);
			if (needsMax(s)) {
				stacks.setStackInSlot(i, maximize(p, s.copy()));
			}
		});
	}

	/** Right-click equip: level the held relic first. */
	static void maximizeHeld(ServerPlayer p, InteractionHand hand) {
		ItemStack s = p.getItemInHand(hand);
		if (needsMax(s)) {
			p.setItemInHand(hand, maximize(p, s));
		}
	}

	/** Runs Relics' maximize on `relic` (by putting it in the main hand for a moment) and returns the result, marked. */
	private static ItemStack maximize(ServerPlayer p, ItemStack relic) {
		Inventory inv = p.getInventory();
		int sel = inv.selected;
		ItemStack held = inv.getItem(sel);
		ItemStack result = relic;
		try {
			inv.setItem(sel, relic);
			p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(4).withSuppressedOutput(), "relics relic maximize");
			result = inv.getItem(sel);
		} catch (Exception ex) {
			Bubble.LOG.warn("Couldn't level up {}", relic.getItem(), ex);
		} finally {
			inv.setItem(sel, held);
		}
		if (result.isEmpty()) {
			result = relic;
		}
		CustomData.update(DataComponents.CUSTOM_DATA, result, t -> t.putBoolean(MARK, true));
		return result;
	}
}
