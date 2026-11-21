package moonahmc.bubble.client;

import moonahmc.bubble.Matter;
import moonahmc.bubble.ShopPayloads;
import moonahmc.bubble.client.net.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "Sells for 12 Strange Matter" on the tooltip of items in your own inventory, so you know what the sell slot would
 * give before you sell. The server works the number out (it knows the prices and your Eidolon sell bonus); answers are
 * kept for a minute.
 */
final class SellTooltip {
	private static final long KEEP_MS = 60_000;
	private static final int MAX = 512;

	private record Known(ItemStack stack, double value, long at) {
	}

	/** Keyed by item + components + count. */
	private static final Map<Integer, Known> known = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, Known> e) {
			return size() > MAX;
		}
	};
	private static final Map<Integer, Long> asked = new java.util.HashMap<>();

	private SellTooltip() {
	}

	private static int key(ItemStack s) {
		return ItemStack.hashItemAndComponents(s) * 31 + s.getCount();
	}

	static void register() {
		NeoForge.EVENT_BUS.addListener((ItemTooltipEvent e) -> {
			ItemStack s = e.getItemStack();
			Minecraft mc = Minecraft.getInstance();
			if (s.isEmpty() || !ClientState.hasBalance || mc.player == null || e.getEntity() != mc.player || !inMyInventory(mc, s)) {
				return;
			}
			long now = net.minecraft.Util.getMillis();
			int k = key(s);
			Known kn = known.get(k);
			if (kn != null && ItemStack.matches(kn.stack(), s) && now - kn.at() < KEEP_MS) {
				if (kn.value() < 0) {
					e.getToolTip().add(Component.literal("Can't be sold").withStyle(ChatFormatting.DARK_RED));
				} else if (kn.value() > 0) {
					String each = s.getCount() > 1 ? " (" + Matter.format(kn.value() / s.getCount()) + " each)" : "";
					e.getToolTip().add(Component.literal("Sells for " + Matter.format(kn.value()) + " Strange Matter" + each).withStyle(ChatFormatting.DARK_AQUA));
				}
				return;
			}
			Long at = asked.get(k);
			if (at == null || now - at > 3000) {
				asked.put(k, now);
				if (asked.size() > MAX) {
					asked.clear();
				}
				ClientPlayNetworking.send(new ShopPayloads.Worth(java.util.List.of(s.copy())));
			}
		});
	}

	/** Only for things you're carrying (not chest contents, JEI's list or the shop's own tiles). */
	private static boolean inMyInventory(Minecraft mc, ItemStack s) {
		if (!(mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> hs)) {
			return false;
		}
		var slot = hs.getSlotUnderMouse();
		return slot != null && slot.getItem() == s && slot.container instanceof net.minecraft.world.entity.player.Inventory;
	}

	static void answered(ShopPayloads.WorthResult r) {
		long now = net.minecraft.Util.getMillis();
		for (int i = 0; i < r.stacks().size(); i++) {
			ItemStack s = r.stacks().get(i);
			known.put(key(s), new Known(s, r.values().get(i), now));
		}
	}

	/** Your sell bonus may have changed (a new run, an Eidolon upgrade): ask again. */
	static void forget() {
		known.clear();
		asked.clear();
	}
}
