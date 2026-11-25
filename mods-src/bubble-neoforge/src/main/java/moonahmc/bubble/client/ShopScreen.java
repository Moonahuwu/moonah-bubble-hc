package moonahmc.bubble.client;

import moonahmc.bubble.ShopPayloads;
import moonahmc.bubble.client.net.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static moonahmc.bubble.client.BubbleScreen.ACCENT;
import static moonahmc.bubble.client.BubbleScreen.CARD;
import static moonahmc.bubble.client.BubbleScreen.CARD_HOVER;
import static moonahmc.bubble.client.BubbleScreen.DANGER;
import static moonahmc.bubble.client.BubbleScreen.MUTED;
import static moonahmc.bubble.client.BubbleScreen.TEXT;
import static moonahmc.bubble.client.BubbleScreen.roundRect;

/**
 * The Strange Matter shop. Tabs on the left, items on the right; click to buy (×1/×5/×16/×64 picked at the top).
 * Search looks through every tab; middle-click stars an item into the Favourites tab; the sort, amount, favourites and
 * last tab are remembered (config/bubble-client.json).
 * The Charging tab fills Oritech (Team Reborn Energy) gear. Operators get an Edit mode to change prices and amounts,
 * add the item they're holding, and add or remove tabs. Every change shows up for everyone right away.
 */
public final class ShopScreen extends ScaledScreen {
	@Override
	protected int panelWidth() {
		return W;
	}

	@Override
	protected int panelHeight() {
		return H;
	}

	private static final int W = 440;
	private static final int H = 292;
	private static final int TAB_W = 108;
	private static final int TILE_W = 56;
	private static final int TILE_H = 64;
	private static final int GAP = 5;
	private static final int COLS = 5;
	private static final int ROW_H = 30;
	private static final int MATTER = 0xFF8FE3F0;

	private static int tab;
	private final Screen parent;
	private int left;
	private int top;
	private int scroll;
	/** First tab shown in the tab column (it scrolls when there are more tabs than fit). */
	private static int tabScroll;
	/** Top bar: false = Shop, true = Gamble (the case tabs). */
	private static boolean gambleMode;
	private static final int TAB_STEP = 22;
	private boolean editing;
	private int selected = -1;

	private BubbleScreen.FlatButton editButton;
	private BubbleScreen.FlatButton casesButton;
	// ---- subtle motion: tile hover lift, sliding tab highlight, grid slide on tab change, balance counting
	private long lastFrame;
	private float dt;
	private final java.util.Map<Integer, Float> hoverAnim = new java.util.HashMap<>();
	private int animTab = -1;
	private boolean animMode;
	private long tabAnimAt;
	private float tabHighlightY = Float.NaN;
	private double shownBalance = Double.NaN;
	private EditBox priceField;
	private EditBox countField;
	private EditBox sectionField;
	private final List<BubbleScreen.FlatButton> editorButtons = new ArrayList<>();
	private BubbleScreen.FlatButton addSectionButton;
	private BubbleScreen.FlatButton removeSectionButton;
	private BubbleScreen.FlatButton chargeAllButton;
	private EditBox urlField;
	private BubbleScreen.FlatButton playVideoButton;
	private EditBox searchBox;
	private String search = "";
	private BubbleScreen.FlatButton sortButton;
	/** ×1, ×5, ×16, ×64 (index = ClientSettings.shopAmount). */
	private final List<BubbleScreen.FlatButton> amountButtons = new ArrayList<>();
	/** The "Favourites" row at the top of the Shop tab list is the one picked. */
	private static boolean favTab;
	/** The last tab and Shop/Gamble choice have been put back from bubble-client.json (once per game). */
	private static boolean restored;
	private static final int[] AMOUNTS = {1, 5, 16, 64};
	private static final String[] SORTS = {"Shop order", "Cheapest", "Priciest", "A\u2013Z"};
	/** Tab-list row meaning the Favourites tab. */
	private static final int FAV = -1;
	private static final int STAR = 0xFFF2C14E;

	/** One tile: the offer and where it really lives (buys send these indices). */
	private record Item(int section, int index, ShopPayloads.Offer offer) {
	}

	public ShopScreen(Screen parent) {
		super(Component.literal("Shop"));
		this.parent = parent;
		ClientPlayNetworking.send(new ShopPayloads.Request());
	}

	private int gridX() {
		return left + 14 + TAB_W + 8;
	}

	private int gridY() {
		return top + 60;
	}

	private int gridW() {
		return W - 14 - (gridX() - left);
	}

	private int gridH() {
		return H - 60 - 14 - (editing && selected >= 0 ? 52 : 0);
	}

	@Override
	protected void init() {
		fit();
		left = (width - W) / 2;
		top = (height - H) / 2;
		editorButtons.clear();
		amountButtons.clear();
		if (ClientState.catalog != null) {
			restoreTab(ClientState.catalog);
		}
		editButton = addRenderableWidget(new BubbleScreen.FlatButton(left + W - 14 - 52, top + 38, 52, 16, Component.literal("Edit"), () -> {
			editing = !editing;
			selected = -1;
			rebuildWidgets();
		}));
		editButton.ghost = true;
		editButton.selected = editing;
		editButton.visible = canEdit();
		casesButton = addRenderableWidget(new BubbleScreen.FlatButton(left + 14, top + H - 14 - 16, TAB_W, 16, Component.literal("Edit cases"),
				() -> minecraft.setScreen(new CaseEditorScreen(this))));
		casesButton.ghost = true;
		casesButton.color = 0xFFF2C14E;
		// Top bar: Shop | Gamble
		BubbleScreen.FlatButton shopMode = addRenderableWidget(new BubbleScreen.FlatButton(left + 18, top + 12, 78, 22, Component.literal("Shop"), () -> setMode(false)));
		shopMode.ghost = gambleMode;
		shopMode.selected = !gambleMode;
		BubbleScreen.FlatButton gambleMode2 = addRenderableWidget(new BubbleScreen.FlatButton(left + 100, top + 12, 78, 22, Component.literal("Gamble"), () -> setMode(true)));
		gambleMode2.ghost = !gambleMode;
		gambleMode2.selected = gambleMode;
		gambleMode2.color = 0xFFF2C14E;
		BubbleScreen.FlatButton eidolons = addRenderableWidget(new BubbleScreen.FlatButton(left + 182, top + 12, 82, 22, Component.literal("Eidolons"),
				() -> minecraft.setScreen(new EidolonScreen(this))));
		eidolons.ghost = true;
		eidolons.color = 0xFFD36BFF;

		// Search (every tab), sort, and how many to buy
		searchBox = addRenderableWidget(new EditBox(font, left + 14, top + 39, TAB_W, 15, Component.literal("Search")));
		searchBox.setMaxLength(40);
		searchBox.setValue(search);
		searchBox.setHint(Component.literal("Search all tabs...").withStyle(ChatFormatting.DARK_GRAY));
		searchBox.setResponder(v -> {
			if (!v.equals(search)) {
				search = v;
				scroll = 0;
			}
		});
		int hx = left + W - 14 - (canEdit() ? 52 + 6 : 0);
		for (int i = AMOUNTS.length - 1; i >= 0; i--) {
			int a = i;
			hx -= 24;
			BubbleScreen.FlatButton b = addRenderableWidget(new BubbleScreen.FlatButton(hx, top + 38, 24, 16, Component.literal("\u00D7" + AMOUNTS[i]), () -> {
				ClientSettings.shopAmount = a;
				ClientSettings.save();
				updateVisibility();
			}));
			b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Buy " + AMOUNTS[i] + " at a time")));
			amountButtons.add(0, b);
			hx -= 2;
		}
		sortButton = addRenderableWidget(new BubbleScreen.FlatButton(hx - 4 - 70, top + 38, 70, 16, Component.literal(SORTS[ClientSettings.shopSort]), () -> {
			ClientSettings.shopSort = (ClientSettings.shopSort + 1) % SORTS.length;
			ClientSettings.save();
			sortButton.setMessage(Component.literal(SORTS[ClientSettings.shopSort]));
			scroll = 0;
		}));
		sortButton.ghost = true;
		sortButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("How the items are sorted (click to change)")));

		// Editor for the selected item (edit mode)
		int ey = top + H - 14 - 44;
		int ex = gridX();
		priceField = addRenderableWidget(new EditBox(font, ex + 36, ey + 25, 50, 14, Component.literal("Price")));
		priceField.setFilter(t -> t.matches("[0-9]{0,7}"));
		countField = addRenderableWidget(new EditBox(font, ex + 116, ey + 25, 24, 14, Component.literal("Amount")));
		countField.setFilter(t -> t.matches("[0-9]{0,2}"));
		editorButtons.add(addRenderableWidget(new BubbleScreen.FlatButton(ex + 146, ey + 24, 36, 16, Component.literal("Save"), this::saveSelected)));
		editorButtons.add(ghost(addRenderableWidget(new BubbleScreen.FlatButton(ex + 186, ey + 24, 18, 16, Component.literal("<"), () -> move(-1)))));
		editorButtons.add(ghost(addRenderableWidget(new BubbleScreen.FlatButton(ex + 206, ey + 24, 18, 16, Component.literal(">"), () -> move(1)))));
		BubbleScreen.FlatButton remove = addRenderableWidget(new BubbleScreen.FlatButton(ex + 228, ey + 24, 60, 16, Component.literal("Remove"),
				() -> edit(ShopPayloads.EDIT_REMOVE, selected, 0, 0, "")));
		remove.color = DANGER;
		editorButtons.add(remove);

		// Tab management (edit mode)
		sectionField = addRenderableWidget(new EditBox(font, left + 14, top + H - 14 - 38, TAB_W, 14, Component.literal("New tab")));
		sectionField.setMaxLength(24);
		sectionField.setHint(Component.literal("New tab name").withStyle(ChatFormatting.DARK_GRAY));
		addSectionButton = addRenderableWidget(new BubbleScreen.FlatButton(left + 14, top + H - 14 - 20, 52, 16, Component.literal("+ Tab"), () -> {
			if (!sectionField.getValue().isBlank()) {
				edit(ShopPayloads.EDIT_ADD_SECTION, 0, 0, 0, sectionField.getValue());
				sectionField.setValue("");
			}
		}));
		removeSectionButton = addRenderableWidget(new BubbleScreen.FlatButton(left + 14 + 56, top + H - 14 - 20, 52, 16, Component.literal("Delete"),
				() -> {
					edit(ShopPayloads.EDIT_REMOVE_SECTION, 0, 0, 0, "");
					tab = Math.max(0, tab - 1);
				}));
		removeSectionButton.color = DANGER;
		removeSectionButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Delete this tab (and everything in it)")));

		chargeAllButton = addRenderableWidget(new BubbleScreen.FlatButton(left + W - 14 - 120, gridY(), 120, 18, Component.literal("Charge all"),
				() -> ClientPlayNetworking.send(new ShopPayloads.Charge(-1))));
		// Video tab: paste a link, pay, everyone sees it
		urlField = addRenderableWidget(new EditBox(font, gridX(), gridY() + 62, gridW() - 14, 16, Component.literal("Video link")));
		urlField.setMaxLength(moonahmc.bubble.VideoShare.MAX_URL);
		urlField.setHint(Component.literal("Paste a link: https://youtube.com/watch?v=...").withStyle(ChatFormatting.DARK_GRAY));
		playVideoButton = addRenderableWidget(new BubbleScreen.FlatButton(gridX(), gridY() + 84, 200, 18, Component.literal("Play for everyone"), () -> {
			String link = urlField.getValue().trim();
			if (!link.isEmpty()) {
				ClientPlayNetworking.send(new moonahmc.bubble.VideoShare.Request(link));
				urlField.setValue("");
			}
		}));
		updateVisibility();
		if (currentSection() != null && currentSection().charging()) {
			ClientPlayNetworking.send(new ShopPayloads.ChargeQuery());
		}
	}

	private static BubbleScreen.FlatButton ghost(BubbleScreen.FlatButton b) {
		b.ghost = true;
		return b;
	}

	/** Looking at the Shop side while an admin has it closed (ops in edit mode still see it). */
	private boolean closedHere() {
		return ClientState.catalog != null && ClientState.catalog.shopClosed() && !gambleMode && !editing;
	}

	private boolean canEdit() {
		return ClientState.catalog != null && ClientState.catalog.canEdit();
	}

	private void updateVisibility() {
		ShopPayloads.Section sec = currentSection();
		boolean itemEditor = editing && selected >= 0 && sec != null && selected < sec.offers().size();
		priceField.visible = itemEditor;
		countField.visible = itemEditor;
		for (BubbleScreen.FlatButton b : editorButtons) {
			b.visible = itemEditor;
		}
		sectionField.visible = editing;
		addSectionButton.visible = editing;
		removeSectionButton.visible = editing && sec != null;
		chargeAllButton.visible = sec != null && sec.charging() && !closedHere() && !listMode();
		boolean videoTab = videoTab(sec) && !listMode();
		boolean grid = listMode() || (sec != null && !sec.charging() && !videoTab && !closedHere());
		searchBox.visible = !editing;
		sortButton.visible = !editing && grid;
		for (int i = 0; i < amountButtons.size(); i++) {
			BubbleScreen.FlatButton b = amountButtons.get(i);
			b.visible = !editing && !gambleMode && grid;
			b.ghost = ClientSettings.shopAmount != i;
			b.selected = ClientSettings.shopAmount == i;
		}
		urlField.visible = videoTab;
		playVideoButton.visible = videoTab;
		if (videoTab) {
			String link = urlField.getValue().trim();
			playVideoButton.active = moonahmc.bubble.VideoShare.validUrl(link);
			playVideoButton.setMessage(Component.literal("Play for everyone · " + String.format("%,d", videoPrice(sec))));
		}
		editButton.visible = canEdit();
		editButton.selected = editing;
		casesButton.visible = canEdit() && gambleMode && !editing;
	}

	/** The Video tab as players see it (ops in Edit mode get the normal grid, to change the price). */
	private boolean videoTab(ShopPayloads.Section sec) {
		return sec != null && sec.video() && !editing && !closedHere();
	}

	private static int videoPrice(ShopPayloads.Section sec) {
		return sec.offers().isEmpty() ? 0 : sec.offers().get(0).price();
	}

	private void drawVideo(GuiGraphics ctx) {
		ShopPayloads.Section sec = currentSection();
		roundRect(ctx, gridX(), gridY(), gridW() - 14, 54, 6, CARD);
		BubbleScreen.bigText(ctx, font, "Show everyone a video", gridX() + 10, gridY() + 8);
		ctx.drawString(font, Component.literal("YouTube, Twitch, or a link to a video file. It plays in a window"), gridX() + 10, gridY() + 26, MUTED, false);
		ctx.drawString(font, Component.literal("on everyone's screen. Costs " + String.format("%,d", videoPrice(sec)) + " Strange Matter. F7 hides it."),
				gridX() + 10, gridY() + 38, MUTED, false);
		String now = VideoOverlay.playing() ? "Now playing: " + VideoOverlay.by() + "'s video" + (VideoOverlay.hidden() ? " (hidden for you, F7 shows it)" : "")
				: "Nothing is playing right now.";
		ctx.drawString(font, Component.literal(font.plainSubstrByWidth(now, gridW() - 14)), gridX(), gridY() + 110, VideoOverlay.playing() ? ACCENT : MUTED, false);
		if (!VideoOverlay.available()) {
			ctx.drawString(font, Component.literal("You don't have WaterMedia installed, so you won't see videos."), gridX(), gridY() + 124, 0xFFEF7D7D, false);
		}
	}

	private ShopPayloads.Section currentSection() {
		ShopPayloads.Catalog c = ClientState.catalog;
		if (c == null || c.sections().isEmpty()) {
			return null;
		}
		restoreTab(c);
		java.util.List<Integer> tabList = shown();
		if (tabList.isEmpty()) {
			return null;
		}
		if (!tabList.contains(tab)) {
			tab = tabList.get(0);
		}
		return c.sections().get(tab);
	}

	/** The first time the shop opens in a game: back to the tab (and Shop/Gamble side) you had open last time. */
	private static void restoreTab(ShopPayloads.Catalog c) {
		if (restored || c.sections().isEmpty()) {
			return;
		}
		restored = true;
		gambleMode = ClientSettings.shopGamble;
		for (int i = 0; i < c.sections().size(); i++) {
			if (c.sections().get(i).name().equals(ClientSettings.shopTab) && c.sections().get(i).gamble() == gambleMode) {
				tab = i;
				return;
			}
		}
	}

	private static void rememberTab() {
		ShopPayloads.Catalog c = ClientState.catalog;
		if (c != null && tab >= 0 && tab < c.sections().size()) {
			ClientSettings.shopTab = c.sections().get(tab).name();
		}
		ClientSettings.shopGamble = gambleMode;
		ClientSettings.save();
	}

	private boolean searching() {
		return !editing && !search.isBlank();
	}

	/** Showing search results or the Favourites tab instead of one tab. */
	private boolean listMode() {
		return !editing && (searching() || (favTab && !gambleMode));
	}

	private static String itemId(ItemStack s) {
		return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
	}

	private int buyAmount() {
		return gambleMode ? 1 : AMOUNTS[Math.max(0, Math.min(AMOUNTS.length - 1, ClientSettings.shopAmount))];
	}

	/** The tiles being shown, in order. Edit mode always shows the tab as it is, so edit indices stay right. */
	private List<Item> view() {
		List<Item> out = new ArrayList<>();
		ShopPayloads.Catalog c = ClientState.catalog;
		if (c == null) {
			return out;
		}
		if (listMode()) {
			String q = search.trim().toLowerCase(java.util.Locale.ROOT);
			boolean find = searching();
			for (int si : shown()) {
				ShopPayloads.Section s = c.sections().get(si);
				if (s.charging() || s.video()) {
					continue;
				}
				for (int i = 0; i < s.offers().size(); i++) {
					ShopPayloads.Offer o = s.offers().get(i);
					boolean match = find
							? o.stack().getHoverName().getString().toLowerCase(java.util.Locale.ROOT).contains(q) || itemId(o.stack()).contains(q)
							: ClientSettings.favourites.contains(itemId(o.stack()));
					if (match) {
						out.add(new Item(si, i, o));
					}
				}
			}
		} else {
			ShopPayloads.Section sec = currentSection();
			if (sec != null) {
				for (int i = 0; i < sec.offers().size(); i++) {
					out.add(new Item(tab, i, sec.offers().get(i)));
				}
			}
		}
		if (!editing) {
			switch (ClientSettings.shopSort) {
				case 1 -> out.sort(java.util.Comparator.comparingInt(it -> it.offer().price()));
				case 2 -> out.sort(java.util.Comparator.comparingInt((Item it) -> it.offer().price()).reversed());
				case 3 -> out.sort(java.util.Comparator.comparing(it -> it.offer().stack().getHoverName().getString().toLowerCase(java.util.Locale.ROOT)));
				default -> {
				}
			}
		}
		return out;
	}

	private void toggleFavourite(ItemStack stack) {
		String id = itemId(stack);
		if (!ClientSettings.favourites.remove(id)) {
			ClientSettings.favourites.add(id);
		}
		if (ClientSettings.favourites.isEmpty()) {
			favTab = false;
		}
		ClientSettings.save();
		if (minecraft != null) {
			minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
					net.minecraft.sounds.SoundEvents.NOTE_BLOCK_CHIME.value(), ClientSettings.favourites.contains(id) ? 1.6f : 1.0f, 0.4f));
		}
	}

	private void clearSearch() {
		search = "";
		if (searchBox != null) {
			searchBox.setValue("");
		}
	}

	/** The tab column: the Favourites row (Shop side, when you have any) and then the real tabs. */
	private java.util.List<Integer> tabRows() {
		java.util.List<Integer> rows = shown();
		if (!gambleMode && !editing && !ClientSettings.favourites.isEmpty()) {
			rows.add(0, FAV);
		}
		return rows;
	}

	private int selectedRow() {
		return favTab && !gambleMode && !editing ? FAV : tab;
	}

	/** A thin scroll bar at the right edge, when there's more than fits. */
	private void scrollBar(GuiGraphics ctx, int y, int h, int total, int visible, int at) {
		if (total <= visible) {
			return;
		}
		int x = left + W - 9;
		int max = total - visible;
		int barH = Math.max(14, h * visible / total);
		int barY = y + Math.round((h - barH) * (Math.min(at, max) / (float) max));
		roundRect(ctx, x, y, 3, h, 1, 0x22FFFFFF);
		roundRect(ctx, x, barY, 3, barH, 1, 0x88FFFFFF);
	}

	/** Called when a new catalogue arrives (an op changed something). */
	void refresh() {
		boolean side = gambleMode;
		ShopPayloads.Section sec = currentSection();
		if (side != gambleMode) {
			rebuildWidgets();
			return;
		}
		if (sec != null && selected >= sec.offers().size()) {
			selected = -1;
		}
		if (!canEdit()) {
			editing = false;
		}
		updateVisibility();
	}

	private void select(int index) {
		selected = index;
		ShopPayloads.Section sec = currentSection();
		if (sec != null && index >= 0 && index < sec.offers().size()) {
			ShopPayloads.Offer o = sec.offers().get(index);
			priceField.setValue(Integer.toString(o.price()));
			countField.setValue(Integer.toString(o.stack().getCount()));
		}
		updateVisibility();
	}

	private void saveSelected() {
		int price = parse(priceField.getValue(), 0);
		int count = parse(countField.getValue(), 1);
		edit(ShopPayloads.EDIT_SET, selected, price, count, "");
	}

	private void move(int dir) {
		ShopPayloads.Section sec = currentSection();
		if (sec == null || selected < 0) {
			return;
		}
		edit(ShopPayloads.EDIT_MOVE, selected, 0, dir, "");
		selected = Math.max(0, Math.min(sec.offers().size() - 1, selected + dir));
	}

	private void edit(int action, int index, int price, int count, String text) {
		ClientPlayNetworking.send(new ShopPayloads.Edit(action, tab, index, price, count, text));
		if (action == ShopPayloads.EDIT_REMOVE) {
			selected = -1;
			updateVisibility();
		}
	}

	private static int parse(String s, int fallback) {
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	@Override
	public void onClose() {
		if (minecraft != null) {
			minecraft.setScreen(parent);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ------------------------------------------------------------------ input

	@Override
	protected boolean clicked(double mx, double my, int button) {
		if (super.clicked(mx, my, button)) {
			return true;
		}
		ShopPayloads.Catalog c = ClientState.catalog;
		if (c == null) {
			return false;
		}
		// Tabs
		java.util.List<Integer> tabList = tabRows();
		clampTabScroll(tabList.size());
		for (int k = tabScroll; k < tabList.size() && k < tabScroll + visibleTabs(); k++) {
			int i = tabList.get(k);
			int ty = top + 60 + (k - tabScroll) * TAB_STEP;
			if (mx >= left + 14 && mx < left + 14 + TAB_W && my >= ty && my < ty + 20) {
				if (searching()) {
					clearSearch();
				}
				if (i == FAV) {
					if (!favTab) {
						favTab = true;
						scroll = 0;
						selected = -1;
					}
				} else if (tab != i || favTab) {
					favTab = false;
					tab = i;
					scroll = 0;
					selected = -1;
					if (c.sections().get(i).charging()) {
						ClientPlayNetworking.send(new ShopPayloads.ChargeQuery());
					}
					rememberTab();
				}
				updateVisibility();
				return true;
			}
		}
		ShopPayloads.Section sec = currentSection();
		if (sec == null || closedHere() || (!listMode() && videoTab(sec))) {
			return false;
		}
		if (sec.charging() && !listMode()) {
			ShopPayloads.ChargeList list = ClientState.charges;
			if (list != null) {
				for (int i = 0; i < list.items().size(); i++) {
					int ry = gridY() + 26 + (i - scroll) * (ROW_H + 4);
					int bx = left + W - 14 - 70;
					if (ry >= gridY() + 26 && ry + ROW_H <= gridY() + gridH() && mx >= bx && mx < bx + 62 && my >= ry + 7 && my < ry + 23) {
						ClientPlayNetworking.send(new ShopPayloads.Charge(list.items().get(i).slot()));
						return true;
					}
				}
			}
			return false;
		}
		int hit = tileAt(mx, my);
		if (hit == -2) {
			// "Add held item" tile
			edit(ShopPayloads.EDIT_ADD_HELD, 0, 0, 0, "");
			return true;
		}
		if (hit >= 0) {
			Item it = view().get(hit);
			if (editing) {
				select(hit);
			} else if (button == 2) {
				if (!gambleMode) {
					toggleFavourite(it.offer().stack());
				}
			} else if (button == 0) {
				int times = gambleMode || buyAmount() == 1 ? (hasShiftDown() ? 5 : 1) : buyAmount();
				ClientPlayNetworking.send(new ShopPayloads.Buy(it.section(), it.index(), times));
			}
			return true;
		}
		return false;
	}

	/** Which tile is under the mouse: an index into view(), -2 for the "add held item" tile, -1 for none. */
	private int tileAt(double mx, double my) {
		int size = view().size();
		int n = size + (editing ? 1 : 0);
		for (int i = 0; i < n; i++) {
			int[] pos = tilePos(i);
			if (pos == null) {
				continue;
			}
			if (mx >= pos[0] && mx < pos[0] + TILE_W && my >= pos[1] && my < pos[1] + TILE_H) {
				return i == size ? -2 : i;
			}
		}
		return -1;
	}

	private int[] tilePos(int i) {
		int row = i / COLS - scroll;
		int col = i % COLS;
		int x = gridX() + col * (TILE_W + GAP);
		int y = gridY() + row * (TILE_H + GAP);
		if (row < 0 || y + TILE_H > gridY() + gridH()) {
			return null;
		}
		return new int[]{x, y};
	}

	@Override
	protected boolean scrolled(double mx, double my, double h, double v) {
		ShopPayloads.Catalog cat = ClientState.catalog;
		if (cat != null && mx >= left + 14 && mx < left + 14 + TAB_W && my >= top + 60 && my < top + H - 14) {
			tabScroll -= (int) Math.signum(v);
			clampTabScroll(tabRows().size());
			return true;
		}
		ShopPayloads.Section sec = currentSection();
		if (sec == null || (!listMode() && videoTab(sec))) {
			return true;
		}
		int max;
		if (sec.charging() && !listMode()) {
			int items = ClientState.charges == null ? 0 : ClientState.charges.items().size();
			int visible = Math.max(1, (gridH() - 26) / (ROW_H + 4));
			max = Math.max(0, items - visible);
		} else {
			int rows = (view().size() + (editing ? 1 : 0) + COLS - 1) / COLS;
			int visible = Math.max(1, (gridH() + GAP) / (TILE_H + GAP));
			max = Math.max(0, rows - visible);
		}
		scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(v)));
		return true;
	}

	/** Real section indices shown for the current top-bar choice (Shop or Gamble). */
	private java.util.List<Integer> shown() {
		java.util.List<Integer> out = new java.util.ArrayList<>();
		ShopPayloads.Catalog c = ClientState.catalog;
		if (c != null) {
			for (int i = 0; i < c.sections().size(); i++) {
				if (c.sections().get(i).gamble() == gambleMode) {
					out.add(i);
				}
			}
		}
		return out;
	}

	private void setMode(boolean gamble) {
		if (gambleMode == gamble) {
			return;
		}
		gambleMode = gamble;
		java.util.List<Integer> tabList = shown();
		tab = tabList.isEmpty() ? 0 : tabList.get(0);
		ShopPayloads.Catalog cat = ClientState.catalog;
		if (cat != null) {
			// back to the last tab you had open on this side, if it's still there
			for (int i : tabList) {
				if (cat.sections().get(i).name().equals(ClientSettings.shopTab)) {
					tab = i;
				}
			}
		}
		favTab = false;
		rememberTab();
		tabScroll = 0;
		scroll = 0;
		selected = -1;
		rebuildWidgets();
	}

	/** How many tabs fit in the column (fewer while the edit tools sit at the bottom). */
	private int visibleTabs() {
		int bottom = top + H - (editing ? 58 : canEdit() && gambleMode ? 34 : 14);
		return Math.max(1, (bottom - (top + 60) + 2) / TAB_STEP);
	}

	private void clampTabScroll(int count) {
		tabScroll = Math.max(0, Math.min(tabScroll, Math.max(0, count - visibleTabs())));
	}

	/** Little arrows above/below the tab column when there are more tabs than fit. */
	private void drawTabArrows(GuiGraphics ctx, int count) {
		int x = left + 14 + TAB_W / 2;
		if (tabScroll > 0) {
			ctx.drawString(font, "\u25B2 more", x - font.width("\u25B2 more") / 2, top + 50, MUTED, false);
		}
		if (tabScroll + visibleTabs() < count) {
			int y = top + 60 + visibleTabs() * TAB_STEP - 1;
			ctx.drawString(font, "\u25BC scroll for more", x - font.width("\u25BC scroll for more") / 2, Math.min(y, top + H - 11), MUTED, false);
		}
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
		super.renderBackground(ctx, mouseX, mouseY, delta);
		ctx.fill(0, 0, width, height, 0x55000000);
		BubbleScreen.panel(ctx, left, top, W, H);

		long nowMs = net.minecraft.Util.getMillis();
		dt = lastFrame == 0 ? 0f : Math.min(0.1f, (nowMs - lastFrame) / 1000f);
		lastFrame = nowMs;
		if (tab != animTab || gambleMode != animMode) {
			if (animTab != -1) {
				tabAnimAt = nowMs;
			}
			animTab = tab;
			animMode = gambleMode;
			hoverAnim.clear();
		}
		// Balance (counts up/down to the real number)
		if (Double.isNaN(shownBalance) || Math.abs(ClientState.balance - shownBalance) < 0.5) {
			shownBalance = ClientState.balance;
		} else {
			shownBalance += (ClientState.balance - shownBalance) * Math.min(1f, dt * 9f);
		}
		String bal = moonahmc.bubble.Matter.format(Math.abs(ClientState.balance - shownBalance) < 0.5 ? ClientState.balance : Math.round(shownBalance));
		ctx.pose().pushPose();
		ctx.pose().translate(left + W - 22, top + 16, 0);
		ctx.pose().scale(1.4f, 1.4f, 1);
		int w = font.width(bal);
		ctx.drawString(font, Component.literal(bal), -w, 0, MATTER, false);
		BubbleScreen.matterIcon(ctx, -w - 13, -2, 11);
		ctx.pose().popPose();
		MatterPopups.render(ctx, font, left + W - 22, top + 34);
		if (editing) {
			BubbleScreen.pill(ctx, font, left + W - 14 - 60, top + 39, "EDITING", 0xFFF2C14E);
		}

		ShopPayloads.Catalog c = ClientState.catalog;
		if (c == null) {
			ctx.drawString(font, Component.literal("Loading the shop..."), gridX(), gridY() + 4, MUTED, false);
			return;
		}
		if (c.sections().isEmpty()) {
			ctx.drawString(font, Component.literal("The shop is empty."), gridX(), gridY() + 4, MUTED, false);
		}

		// Tabs
		java.util.List<Integer> tabList = tabRows();
		clampTabScroll(tabList.size());
		float targetY = Float.NaN;
		int picked = selectedRow();
		for (int k = tabScroll; k < tabList.size() && k < tabScroll + visibleTabs(); k++) {
			int i = tabList.get(k);
			int ty = top + 60 + (k - tabScroll) * TAB_STEP;
			boolean hover = mouseX >= left + 14 && mouseX < left + 14 + TAB_W && mouseY >= ty && mouseY < ty + 20;
			roundRect(ctx, left + 14, ty, TAB_W, 20, 5, hover && i != picked ? CARD_HOVER : CARD);
			if (i == picked && !searching()) {
				targetY = ty;
			}
		}
		if (Float.isNaN(targetY)) {
			tabHighlightY = Float.NaN;
		} else {
			tabHighlightY = Float.isNaN(tabHighlightY) ? targetY : approach(tabHighlightY, targetY, 18f);
			int hy = Math.round(tabHighlightY);
			roundRect(ctx, left + 14, hy, TAB_W, 20, 5, 0xFF253029);
			roundRect(ctx, left + 14, hy + 4, 2, 12, 1, ACCENT);
		}
		for (int k = tabScroll; k < tabList.size() && k < tabScroll + visibleTabs(); k++) {
			int i = tabList.get(k);
			int ty = top + 60 + (k - tabScroll) * TAB_STEP;
			if (i == FAV) {
				ctx.drawString(font, Component.literal("\u2605"), left + 23, ty + 6, STAR, false);
				ctx.drawString(font, Component.literal("Favourites"), left + 39, ty + 6, i == picked && !searching() ? TEXT : MUTED, false);
				continue;
			}
			ShopPayloads.Section s = c.sections().get(i);
			ctx.renderItem(s.icon(), left + 19, ty + 2);
			ctx.drawString(font, Component.literal(font.plainSubstrByWidth(s.name(), TAB_W - 30)), left + 39, ty + 6,
					i == picked && !searching() ? TEXT : MUTED, false);
		}
		drawTabArrows(ctx, tabList.size());
		if (tabList.isEmpty()) {
			ctx.drawString(font, Component.literal(gambleMode ? "No cases yet." : "Nothing here."), gridX(), gridY() + 4, MUTED, false);
		}

		ShopPayloads.Section sec = currentSection();
		if (sec == null) {
			return;
		}
		float slide = 1f - ease((nowMs - tabAnimAt) / 180f);
		ctx.pose().pushPose();
		ctx.pose().translate(slide * 14f, 0, 0);
		if (closedHere()) {
			roundRect(ctx, gridX(), gridY(), gridW() - 14, 58, 6, CARD);
			BubbleScreen.bigText(ctx, font, "The shop is closed", gridX() + 12, gridY() + 10);
			ctx.drawString(font, Component.literal("An admin turned buying off. You can still sell items,"), gridX() + 12, gridY() + 30, MUTED, false);
			ctx.drawString(font, Component.literal("and the Gamble tab is open."), gridX() + 12, gridY() + 42, MUTED, false);
		} else if (listMode()) {
			drawGrid(ctx, mouseX, mouseY);
		} else if (sec.charging()) {
			drawCharging(ctx, mouseX, mouseY);
		} else if (videoTab(sec)) {
			drawVideo(ctx);
		} else {
			drawGrid(ctx, mouseX, mouseY);
		}
		ctx.pose().popPose();
		if (editing && selected >= 0 && selected < sec.offers().size()) {
			drawEditor(ctx, sec.offers().get(selected));
		}
	}

	private static float ease(float t) {
		t = Math.max(0f, Math.min(1f, t));
		return 1f - (1f - t) * (1f - t) * (1f - t);
	}

	private float approach(float cur, float target, float speed) {
		return cur + (target - cur) * Math.min(1f, dt * speed);
	}

	private static int lerpColor(int a, int b, float t) {
		int r = 0;
		for (int sh = 0; sh <= 24; sh += 8) {
			int ca = (a >>> sh) & 0xFF;
			int cb = (b >>> sh) & 0xFF;
			r |= (Math.round(ca + (cb - ca) * t) & 0xFF) << sh;
		}
		return r;
	}

	private void drawGrid(GuiGraphics ctx, int mouseX, int mouseY) {
		List<Item> items = view();
		if (items.isEmpty() && !editing) {
			String empty = searching() ? "Nothing matches \"" + search.trim() + "\"."
					: listMode() ? "No favourites yet. Middle-click an item to star it." : "Nothing here yet.";
			ctx.drawString(font, Component.literal(font.plainSubstrByWidth(empty, gridW() - 14)), gridX(), gridY() + 4, MUTED, false);
		}
		int amount = buyAmount();
		int n = items.size() + (editing ? 1 : 0);
		for (int i = 0; i < n; i++) {
			int[] pos = tilePos(i);
			if (pos == null) {
				continue;
			}
			int x = pos[0];
			boolean hover = mouseX >= x && mouseX < x + TILE_W && mouseY >= pos[1] && mouseY < pos[1] + TILE_H;
			float h = approach(hoverAnim.getOrDefault(i, 0f), hover ? 1f : 0f, 16f);
			hoverAnim.put(i, h);
			int y = pos[1] - Math.round(h * 2f);
			if (i == items.size()) {
				roundRect(ctx, x, y, TILE_W, TILE_H, 6, hover ? 0xFF2A3A31 : 0xFF1C2620);
				ctx.drawCenteredString(font, Component.literal("+"), x + TILE_W / 2, y + 18, ACCENT);
				ctx.drawCenteredString(font, Component.literal("Add held"), x + TILE_W / 2, y + 34, MUTED);
				ctx.drawCenteredString(font, Component.literal("item"), x + TILE_W / 2, y + 44, MUTED);
				continue;
			}
			ShopPayloads.Offer o = items.get(i).offer();
			boolean afford = ClientState.balance + 1e-6 >= (double) o.price() * (editing ? 1 : amount);
			int bg = editing && i == selected ? 0xFF2F3A33 : lerpColor(CARD, CARD_HOVER, h);
			roundRect(ctx, x, y, TILE_W, TILE_H, 6, bg);
			if (h > 0.02f && !(editing && i == selected)) {
				int glow = ((Math.round(h * 0x90) & 0xFF) << 24) | ((gambleMode ? 0xF2C14E : ACCENT) & 0xFFFFFF);
				roundRect(ctx, x + 4, y + TILE_H - 2, TILE_W - 8, 2, 1, glow);
			}
			if (editing && i == selected) {
				roundRect(ctx, x, y + TILE_H - 2, TILE_W, 2, 1, 0xFFF2C14E);
			}
			// item, drawn 1.5x
			ctx.pose().pushPose();
			ctx.pose().translate(x + TILE_W / 2f - 12, y + 6, 0);
			ctx.pose().scale(1.5f, 1.5f, 1);
			ctx.renderItem(o.stack(), 0, 0);
			ctx.pose().popPose();
			ctx.pose().pushPose();
			ctx.pose().translate(0, 0, 200);
			if (o.stack().getCount() > 1) {
				String cnt = "\u00D7" + o.stack().getCount();
				ctx.drawString(font, Component.literal(cnt), x + TILE_W - 4 - font.width(cnt), y + 24, TEXT);
			}
			if (!editing && !gambleMode && ClientSettings.favourites.contains(itemId(o.stack()))) {
				ctx.drawString(font, Component.literal("\u2605"), x + 4, y + 3, STAR);
			}
			ctx.pose().popPose();
			Component full = Rarity.itemName(o.stack());
			String name = font.plainSubstrByWidth(full.getString(), TILE_W - 6);
			Component shown = name.equals(full.getString()) ? full : Component.literal(name).withStyle(full.getStyle());
			ctx.drawString(font, shown, x + (TILE_W - font.width(name)) / 2, y + 36, TEXT, false);
			String price = String.format("%,d", o.price());
			int pw = font.width(price) + 12;
			int px = x + (TILE_W - pw) / 2;
			BubbleScreen.matterIcon(ctx, px, y + 49, 9);
			ctx.drawString(font, Component.literal(price), px + 12, y + 50, afford ? MATTER : DANGER, false);
			if (!afford && !editing) {
				// can't afford the chosen amount: dim the tile
				ctx.pose().pushPose();
				ctx.pose().translate(0, 0, 250);
				roundRect(ctx, x, y, TILE_W, TILE_H, 6, 0x70121418);
				ctx.pose().popPose();
			}
		}
		int rows = (n + COLS - 1) / COLS;
		int visible = Math.max(1, (gridH() + GAP) / (TILE_H + GAP));
		scrollBar(ctx, gridY(), gridH(), rows, visible, scroll);
	}

	private void drawEditor(GuiGraphics ctx, ShopPayloads.Offer o) {
		int ex = gridX();
		int ey = top + H - 14 - 44;
		roundRect(ctx, ex, ey, gridW(), 44, 6, 0xFF20262D);
		ctx.renderItem(o.stack(), ex + 6, ey + 3);
		ctx.drawString(font, fit(Rarity.itemName(o.stack()), 150), ex + 26, ey + 8, TEXT, false);
		String sells = "sells for " + moonahmc.bubble.Matter.format(o.sellValue()) + " (price can't go lower)";
		ctx.drawString(font, Component.literal(font.plainSubstrByWidth(sells, gridW() - 180)), ex + gridW() - 8
				- Math.min(gridW() - 180, font.width(sells)), ey + 8, MUTED, false);
		ctx.drawString(font, Component.literal("Price"), ex + 8, ey + 28, MUTED, false);
		ctx.drawString(font, Component.literal("Amt"), ex + 94, ey + 28, MUTED, false);
	}

	private void drawCharging(GuiGraphics ctx, int mouseX, int mouseY) {
		ShopPayloads.ChargeList list = ClientState.charges;
		int rate = ClientState.catalog.chargeRate();
		ctx.drawString(font, Component.literal("1 Strange Matter = " + String.format("%,d", rate) + " energy"), gridX(), gridY() + 5, MUTED, false);
		if (list == null) {
			ctx.drawString(font, Component.literal("Checking your inventory..."), gridX(), gridY() + 30, MUTED, false);
			return;
		}
		if (!list.available()) {
			ctx.drawString(font, Component.literal("No energy mod (Oritech) on this server."), gridX(), gridY() + 30, MUTED, false);
			return;
		}
		if (list.items().isEmpty()) {
			ctx.drawString(font, Component.literal("Nothing in your inventory holds energy."), gridX(), gridY() + 30, MUTED, false);
			ctx.drawString(font, Component.literal("Oritech tools, armor and batteries show up here."), gridX(), gridY() + 42, 0xFF5A616C, false);
			return;
		}
		int total = 0;
		for (ShopPayloads.Chargeable ch : list.items()) {
			total += ch.cost();
		}
		chargeAllButton.setMessage(Component.literal("Charge all · " + String.format("%,d", total)));
		chargeAllButton.active = total > 0;
		for (int i = scroll; i < list.items().size(); i++) {
			ShopPayloads.Chargeable ch = list.items().get(i);
			int ry = gridY() + 26 + (i - scroll) * (ROW_H + 4);
			if (ry + ROW_H > gridY() + gridH()) {
				break;
			}
			int rw = gridW();
			roundRect(ctx, gridX(), ry, rw, ROW_H, 6, CARD);
			ctx.renderItem(ch.stack(), gridX() + 7, ry + 7);
			ctx.drawString(font, fit(Rarity.itemName(ch.stack()), 120), gridX() + 30, ry + 6, TEXT, false);
			// energy bar
			int bx = gridX() + 30;
			int bw = rw - 30 - 86;
			float f = ch.capacity() <= 0 ? 0 : (float) Math.min(1, ch.amount() / (double) ch.capacity());
			roundRect(ctx, bx, ry + 19, bw, 4, 2, 0xFF2E333B);
			roundRect(ctx, bx, ry + 19, Math.round(bw * f), 4, 2, 0xFFF2C14E);
			String amt = compact(ch.amount()) + " / " + compact(ch.capacity());
			ctx.drawString(font, Component.literal(amt), bx + bw - font.width(amt), ry + 6, MUTED, false);
			// button
			int btx = left + W - 14 - 70;
			boolean full = ch.cost() <= 0;
			boolean hover = mouseX >= btx && mouseX < btx + 62 && mouseY >= ry + 7 && mouseY < ry + 23;
			roundRect(ctx, btx, ry + 7, 62, 16, 5, full ? 0xFF23272E : hover ? 0xFFF5D27A : 0xFFF2C14E);
			String label = full ? "Full" : String.format("%,d", ch.cost());
			int lw = font.width(label) + (full ? 0 : 11);
			int lx = btx + (62 - lw) / 2;
			if (!full) {
				BubbleScreen.matterIcon(ctx, lx, ry + 10, 9);
				lx += 11;
			}
			ctx.drawString(font, Component.literal(label), lx, ry + 11, full ? 0xFF5A616C : 0xFF2A2006, false);
		}
		scrollBar(ctx, gridY() + 26, gridH() - 26, list.items().size(), Math.max(1, (gridH() - 26) / (ROW_H + 4)), scroll);
	}

	private static String compact(long v) {
		if (v >= 1_000_000) {
			return String.format("%.1fM", v / 1_000_000.0);
		}
		if (v >= 10_000) {
			return String.format("%.0fk", v / 1000.0);
		}
		return String.format("%,d", v);
	}

	@Override
	protected void renderScaled(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
		super.renderScaled(ctx, mouseX, mouseY, delta);
		ShopPayloads.Section sec = currentSection();
		if (sec == null || closedHere() || (!listMode() && (sec.charging() || videoTab(sec)))) {
			return;
		}
		int hit = tileAt(mouseX, mouseY);
		if (hit >= 0) {
			Item it = view().get(hit);
			ShopPayloads.Offer o = it.offer();
			List<Component> lines = new ArrayList<>(getTooltipFromItem(minecraft, o.stack()));
			lines.add(Component.empty());
			lines.add(Component.literal("Price: " + String.format("%,d", o.price()) + " Strange Matter").withStyle(ChatFormatting.AQUA));
			if (editing) {
				lines.add(Component.literal("Click to edit").withStyle(ChatFormatting.GRAY));
			} else if (gambleMode) {
				lines.add(Component.literal("Click: open 1  ·  Shift-click: open 5").withStyle(ChatFormatting.GRAY));
				lines.add(Component.literal("Luck raises the odds.").withStyle(ChatFormatting.DARK_GRAY));
			} else {
				int n = buyAmount();
				long total = (long) o.price() * n;
				lines.add(Component.literal(n > 1 ? "Click: buy " + n + " for " + String.format("%,d", total)
						: "Click: buy  ·  Shift-click: buy 5").withStyle(ChatFormatting.GRAY));
				double need = total - ClientState.balance;
				if (need > 1e-6) {
					lines.add(Component.literal("Need " + moonahmc.bubble.Matter.format(Math.ceil(need)) + " more Strange Matter").withStyle(ChatFormatting.RED));
				}
				boolean fav = ClientSettings.favourites.contains(itemId(o.stack()));
				lines.add(Component.literal(fav ? "\u2605 Middle-click: take out of Favourites" : "Middle-click: add to Favourites").withStyle(ChatFormatting.DARK_GRAY));
			}
			if (listMode() && ClientState.catalog != null && it.section() < ClientState.catalog.sections().size()) {
				lines.add(Component.literal("In the " + ClientState.catalog.sections().get(it.section()).name() + " tab").withStyle(ChatFormatting.DARK_GRAY));
			}
			ctx.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
		} else if (hit == -2) {
			ctx.renderTooltip(font, List.of(Component.literal("Add the item in your main hand to this tab"),
					Component.literal("(with its amount, enchantments, etc.)").withStyle(ChatFormatting.GRAY)), Optional.empty(), mouseX, mouseY);
		}
	}

	@Override
	public void tick() {
		super.tick();
		updateVisibility();
	}

	/** A (possibly styled) name cut to `w` pixels; kept whole and styled when it fits. */
	private Component fit(Component name, int w) {
		String full = name.getString();
		if (font.width(full) <= w) {
			return name;
		}
		return Component.literal(font.plainSubstrByWidth(full, w)).withStyle(name.getStyle());
	}
}

