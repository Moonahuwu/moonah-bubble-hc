package moonahmc.bubble.client;

import moonahmc.bubble.Matter;
import moonahmc.bubble.ShopPayloads;
import moonahmc.bubble.client.net.ClientPlayNetworking;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static moonahmc.bubble.client.BubbleScreen.BG;
import static moonahmc.bubble.client.BubbleScreen.CARD_HOVER;
import static moonahmc.bubble.client.BubbleScreen.roundRect;

/**
 * Additions to the survival inventory: the Bubble and Shop buttons, your Strange Matter, and the sell slot
 * (right above TrashSlot's trash slot if that mod is installed, otherwise above the inventory's top-left corner). Drop an item in the sell slot to turn it into
 * Strange Matter; shift-click sells every matching item; right-click buys back your last sale.
 */
final class InventoryOverlay {
	private static final int SIZE = 22;
	private static final int MATTER = 0xFF8FE3F0;
	private static ItemStack asked = ItemStack.EMPTY;

	private InventoryOverlay() {
	}

	/** Optional key in Controls; when it isn't set, Ctrl is the quick-sell key. */
	static net.minecraft.client.KeyMapping quickSellKey;

	/** Ctrl (or the chosen key) held right now. Read straight from the keyboard so it never clashes with sprint. */
	private static boolean quickSellDown() {
		Minecraft mc = Minecraft.getInstance();
		if (quickSellKey != null && !quickSellKey.isUnbound()) {
			com.mojang.blaze3d.platform.InputConstants.Key key = quickSellKey.getKey();
			return key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM
					&& com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow().getWindow(), key.getValue());
		}
		return Screen.hasControlDown();
	}

	/** The survival inventory that's open right now, with the widgets added to it. */
	private record Open(InventoryScreen inv, AbstractWidget bubble, AbstractWidget shop) {
	}

	private static Open open;

	static void register() {
		var bus = NeoForge.EVENT_BUS;
		bus.addListener((ScreenEvent.Init.Post e) -> {
			open = null;
			if (!(e.getScreen() instanceof InventoryScreen inv) || !ClientState.available) {
				return;
			}
			Minecraft client = Minecraft.getInstance();
			BubbleScreen.IconButton bubble = new BubbleScreen.IconButton(0, 0,
					() -> client.setScreen(ClientState.postGame != null ? new PostGameScreen() : new BubbleScreen(inv)));
			e.addListener(bubble);
			ShopButton shop = ClientState.hasBalance ? new ShopButton(() -> client.setScreen(new ShopScreen(inv))) : null;
			if (shop != null) {
				e.addListener(shop);
			}
			place(inv, bubble, shop);
			open = new Open(inv, bubble, shop);
		});
		bus.addListener((ScreenEvent.Render.Pre e) -> {
			if (open != null && e.getScreen() == open.inv()) {
				place(open.inv(), open.bubble(), open.shop());
			}
		});
		bus.addListener((ScreenEvent.Render.Post e) -> {
			if (open != null && e.getScreen() == open.inv() && open.shop() != null) {
				render(open.inv(), e.getGuiGraphics(), e.getMouseX(), e.getMouseY());
			}
		});
		bus.addListener((ScreenEvent.MouseButtonPressed.Pre e) -> {
			// The sell slot in the survival inventory
			if (open != null && e.getScreen() == open.inv() && open.shop() != null
					&& click(open.inv(), e.getMouseX(), e.getMouseY(), e.getButton())) {
				e.setCanceled(true);
				return;
			}
			// Ctrl+click an item in your own inventory (any container screen except creative) to sell it instantly.
			if (!(e.getScreen() instanceof AbstractContainerScreen<?> hs) || e.getScreen() instanceof CreativeModeInventoryScreen) {
				return;
			}
			if (e.getButton() != 0 || !ClientState.hasBalance || !quickSellDown()) {
				return;
			}
			Slot slot = hs.getSlotUnderMouse();
			if (slot == null || !slot.hasItem() || !(slot.container instanceof Inventory) || !hs.getMenu().getCarried().isEmpty()) {
				return;
			}
			ClientPlayNetworking.send(new ShopPayloads.QuickSell(slot.index));
			e.setCanceled(true);
		});
		bus.addListener((ScreenEvent.MouseButtonReleased.Pre e) -> {
			if (open != null && e.getScreen() == open.inv() && open.shop() != null && inSlot(open.inv(), e.getMouseX(), e.getMouseY())) {
				e.setCanceled(true);
			}
		});
	}

	/** Keeps the buttons on the inventory's top-right corner (it moves when the recipe book opens). */
	private static void place(InventoryScreen inv, AbstractWidget bubble, AbstractWidget shop) {
		
		bubble.setPosition(inv.getGuiLeft() + 176 - 22, inv.getGuiTop() - 24);
		if (shop != null) {
			shop.setPosition(inv.getGuiLeft() + 176 - 46, inv.getGuiTop() - 24);
		}
	}

	/** Top-left of the sell slot: just above the trash slot, or above the inventory if there isn't one. */
	private static int[] slotPos(InventoryScreen inv) {
		for (Slot s : inv.getMenu().slots) {
			if (s.isActive() && s.getClass().getName().contains("TrashSlot")) {
				return new int[]{inv.getGuiLeft() + s.x - 3, inv.getGuiTop() + s.y - 3 - SIZE - 6};
			}
		}
		// No trash slot: sit above the inventory's top-left corner, in the same row as the buttons (the right side is
		// where JEI's item list and potion effects go).
		return new int[]{inv.getGuiLeft() + 2, inv.getGuiTop() - SIZE - 2};
	}

	private static boolean inSlot(InventoryScreen inv, double mx, double my) {
		int[] p = slotPos(inv);
		return mx >= p[0] && mx < p[0] + SIZE && my >= p[1] && my < p[1] + SIZE;
	}

	private static boolean click(InventoryScreen inv, double mx, double my, int button) {
		if (!inSlot(inv, mx, my)) {
			return false;
		}
		ItemStack cursor = inv.getMenu().getCarried();
		if (button == 0 && !cursor.isEmpty()) {
			ClientPlayNetworking.send(new ShopPayloads.Sell(Screen.hasShiftDown()));
		} else if (button == 1 && cursor.isEmpty()) {
			ClientPlayNetworking.send(new ShopPayloads.BuyBack());
		}
		return true;
	}

	private static void render(InventoryScreen inv, GuiGraphics ctx, int mx, int my) {
		Minecraft mc = Minecraft.getInstance();
		var tr = mc.font;
		

		// Balance, left of the buttons
		String bal = Matter.format(ClientState.balance);
		int right = inv.getGuiLeft() + 176 - 52;
		int w = tr.width(bal) + 18;
		roundRect(ctx, right - w, inv.getGuiTop() - 22, w, 16, 6, BG);
		BubbleScreen.matterAmount(ctx, tr, right - 4, inv.getGuiTop() - 18, bal, MATTER);
		MatterPopups.render(ctx, tr, right - 4, inv.getGuiTop() - 36);

		// The sell slot
		int[] p = slotPos(inv);
		boolean hover = mx >= p[0] && mx < p[0] + SIZE && my >= p[1] && my < p[1] + SIZE;
		ItemStack cursor = inv.getMenu().getCarried();
		ctx.pose().pushPose();
		ctx.pose().translate(0, 0, 100);
		roundRect(ctx, p[0] - 1, p[1] - 1, SIZE + 2, SIZE + 2, 5, 0xFF0B0D10);
		roundRect(ctx, p[0], p[1], SIZE, SIZE, 4, hover ? (cursor.isEmpty() ? CARD_HOVER : 0xFF1E3A44) : BG);
		if (hover && !cursor.isEmpty()) {
			roundRect(ctx, p[0], p[1], SIZE, SIZE, 4, 0x3060D8F0);
		}
		BubbleScreen.matterIcon(ctx, p[0] + 3, p[1] + 3, 16);
		ctx.pose().popPose();

		if (!hover) {
			return;
		}
		List<Component> lines = new ArrayList<>();
		if (!cursor.isEmpty()) {
			if (asked.isEmpty() || !ItemStack.matches(asked, cursor)) {
				asked = cursor.copy();
				ClientPlayNetworking.send(new ShopPayloads.Quote(cursor.copy()));
			}
			ShopPayloads.QuoteResult q = ClientState.quote;
			boolean fresh = q != null && ItemStack.matches(q.stack(), cursor);
			lines.add(Component.literal("Sell ").append(Component.literal(cursor.getCount() + "× ")).append(cursor.getHoverName()));
			if (fresh && q.total() < 0) {
				lines.add(Component.literal("can't be sold").withStyle(ChatFormatting.RED));
			} else {
				lines.add(Component.literal(fresh ? "for " + Matter.format(q.total()) + " Strange Matter" : "for ...").withStyle(ChatFormatting.AQUA));
				lines.add(Component.literal("Shift-click: sell every matching item").withStyle(ChatFormatting.DARK_GRAY));
			}
		} else {
			lines.add(Component.literal("Strange Matter exchange").withStyle(ChatFormatting.AQUA));
			lines.add(Component.literal("Drop items here to sell them.").withStyle(ChatFormatting.GRAY));
			lines.add(Component.literal("Diamonds, emeralds, ender pearls and things").withStyle(ChatFormatting.GRAY));
			lines.add(Component.literal("with long crafting trees are worth the most.").withStyle(ChatFormatting.GRAY));
			lines.add(Component.literal("Ctrl+click any item to sell it instantly.").withStyle(ChatFormatting.DARK_AQUA));
			ShopPayloads.QuoteResult q = ClientState.quote;
			if (q == null) {
				asked = ItemStack.EMPTY;
				ClientPlayNetworking.send(new ShopPayloads.Quote(ItemStack.EMPTY));
				ClientState.quote = new ShopPayloads.QuoteResult(ItemStack.EMPTY, 0, ItemStack.EMPTY, 0);
			} else if (!q.lastSold().isEmpty()) {
				lines.add(Component.empty());
				lines.add(Component.literal("Right-click: buy back ").withStyle(ChatFormatting.YELLOW).append(q.lastSold().getHoverName())
						.append(Component.literal(" for " + Matter.format(q.lastSoldFor())).withStyle(ChatFormatting.YELLOW)));
			}
		}
		ctx.pose().pushPose();
		ctx.pose().translate(0, 0, 300);
		ctx.renderTooltip(tr, lines, Optional.empty(), mx, my);
		ctx.pose().popPose();
	}

	/** Round button with a little shopping bag. */
	static final class ShopButton extends AbstractWidget {
		private final Runnable action;

		ShopButton(Runnable action) {
			super(0, 0, 20, 20, Component.literal("Shop"));
			this.action = action;
			setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Shop (Strange Matter)")));
		}

		@Override
		protected void renderWidget(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
			roundRect(ctx, getX(), getY(), width, height, 6, isHovered() ? CARD_HOVER : BG);
			int cx = getX() + width / 2;
			int cy = getY() + height / 2 + 1;
			int gold = 0xFFF2C14E;
			// handle
			ctx.fill(cx - 3, cy - 7, cx + 3, cy - 6, gold);
			ctx.fill(cx - 4, cy - 6, cx - 3, cy - 3, gold);
			ctx.fill(cx + 3, cy - 6, cx + 4, cy - 3, gold);
			// bag
			roundRect(ctx, cx - 6, cy - 3, 12, 9, 2, gold);
			ctx.fill(cx - 1, cy, cx + 1, cy + 2, 0xFF6B4E12);
		}

		@Override
		public void onClick(double mouseX, double mouseY) {
			action.run();
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput builder) {
			defaultButtonNarrationText(builder);
		}
	}
}
