package moonahmc.bubble.client;

import moonahmc.bubble.Gamble;
import moonahmc.bubble.client.net.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

import static moonahmc.bubble.client.BubbleScreen.ACCENT;
import static moonahmc.bubble.client.BubbleScreen.CARD;
import static moonahmc.bubble.client.BubbleScreen.CARD_HOVER;
import static moonahmc.bubble.client.BubbleScreen.DANGER;
import static moonahmc.bubble.client.BubbleScreen.MUTED;
import static moonahmc.bubble.client.BubbleScreen.TEXT;
import static moonahmc.bubble.client.BubbleScreen.panel;
import static moonahmc.bubble.client.BubbleScreen.roundRect;

/**
 * Ops: each case's odds and contents, from the shop's Gamble side (Edit cases). Odds are weights per tier, shown as a
 * share of the total. Click an item twice to take it out of a tier; click something in your inventory (below) to add a
 * copy of it (enchantments and all) to the selected tier. Saved to config/bubble-cases.json, live right away.
 */
public final class CaseEditorScreen extends ScaledScreen {
	private static final int W = 440;
	private static final int H = 330;
	private static final int SLOT = 22;
	private static final int COLS = 18;
	private static final int GRID_ROWS = 4;
	private static int caseIndex;
	private static int tier;
	private final Screen parent;
	private final String[] oddsText = new String[Gamble.TIERS.length];
	private String oddsFor = "";
	private int left;
	private int top;
	private int scroll;
	private int armed = -1;
	private long armedAt;

	CaseEditorScreen(Screen parent) {
		super(Component.literal("Edit cases"));
		this.parent = parent;
		ClientPlayNetworking.send(new Gamble.CaseEdit(Gamble.CASE_REQUEST, "", 0, 0, List.of()));
	}

	@Override
	protected int panelWidth() {
		return W;
	}

	@Override
	protected int panelHeight() {
		return H;
	}

	void refresh() {
		rebuildWidgets();
	}

	/** Case name styles: "" = its plain colour, then the tier styles and every Eidolon name colour. */
	static final String[] STYLE_IDS;
	static final String[] STYLE_LABELS;

	static {
		List<String> ids = new ArrayList<>(List.of("", "tier_legendary", "tier_epic", "factory_new"));
		List<String> labels = new ArrayList<>(List.of("Plain colour", "Legendary gold", "Epic shimmer", "Factory New"));
		for (moonahmc.bubble.Eidolons.Cosmetic cm : moonahmc.bubble.Eidolons.COSMETICS) {
			if (cm.kind().equals("name")) {
				ids.add(cm.id());
				labels.add(cm.name().replace(" (gradient)", "").replace(" (animated)", ""));
			}
		}
		STYLE_IDS = ids.toArray(String[]::new);
		STYLE_LABELS = labels.toArray(String[]::new);
	}

	private Gamble.CaseInfo current() {
		Gamble.CaseList l = ClientState.caseList;
		if (l == null || l.cases().isEmpty()) {
			return null;
		}
		caseIndex = Math.max(0, Math.min(caseIndex, l.cases().size() - 1));
		return l.cases().get(caseIndex);
	}

	private int gridX() {
		return left + (W - COLS * SLOT) / 2;
	}

	private int gridY() {
		return top + 110;
	}

	private int invY() {
		return gridY() + GRID_ROWS * SLOT + 22;
	}

	private static String fmt(double v) {
		String s = String.format("%.2f", v);
		return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
	}

	@Override
	protected void init() {
		fit();
		left = (width - W) / 2;
		top = (height - H) / 2;
		addRenderableWidget(new BubbleScreen.FlatButton(left + W - 14 - 64, top + H - 30, 64, 20, Component.literal("Back"), this::onClose)).ghost = true;
		Gamble.CaseList list = ClientState.caseList;
		Gamble.CaseInfo c = current();
		if (c == null) {
			return;
		}
		// case picker
		String[] names = list.cases().stream().map(Gamble.CaseInfo::name).toArray(String[]::new);
		BubbleScreen.Choice pick = addRenderableWidget(new BubbleScreen.Choice(left + W - 14 - 180, top + 14, 180, 16, names, () -> caseIndex, v -> {
			caseIndex = v;
			scroll = 0;
			armed = -1;
		}));
		pick.onChange = this::rebuildWidgets;
		// name style (Prism): how the case's name looks on the case item and in the shop
		String[] styles = new String[STYLE_IDS.length];
		for (int i = 0; i < styles.length; i++) {
			styles[i] = STYLE_LABELS[i];
		}
		int current = java.util.Arrays.asList(STYLE_IDS).indexOf(c.style());
		BubbleScreen.Choice style = addRenderableWidget(new BubbleScreen.Choice(left + W - 14 - 180 - 8 - 130, top + 14, 130, 16, styles,
				() -> Math.max(0, java.util.Arrays.asList(STYLE_IDS).indexOf(c.style())), v -> ClientPlayNetworking.send(
						new Gamble.CaseEdit(Gamble.CASE_SET_STYLE, c.id(), 0, v, List.of()))));
		style.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
				"Name style: how this case's name looks on the case item and in the shop (" + (current <= 0 ? "plain colour" : STYLE_LABELS[current]) + ")")));
		// tier tabs
		double total = c.odds().stream().mapToDouble(Float::doubleValue).sum();
		int tx = left + 14;
		int tw = (W - 28 - 4 * 4) / 5;
		for (int t = 0; t < Gamble.TIERS.length; t++) {
			int index = t;
			double pct = total <= 0 ? 0 : c.odds().get(t) / total * 100;
			BubbleScreen.FlatButton b = addRenderableWidget(new BubbleScreen.FlatButton(tx + t * (tw + 4), top + 40, tw, 18,
					Component.literal(Gamble.TIERS[t] + "  " + fmt(pct) + "%"), () -> {
						tier = index;
						scroll = 0;
						armed = -1;
						rebuildWidgets();
					}));
			b.color = Gamble.TIER_COLORS[t];
			b.ghost = tier != t;
			b.selected = tier == t;
		}
		// odds boxes (kept while typing; reset when the case changes or new data arrives)
		if (!oddsFor.equals(c.id() + c.odds())) {
			oddsFor = c.id() + c.odds();
			for (int t = 0; t < oddsText.length; t++) {
				oddsText[t] = fmt(c.odds().get(t));
			}
		}
		int ox = left + 64;
		for (int t = 0; t < Gamble.TIERS.length; t++) {
			int index = t;
			EditBox box = addRenderableWidget(new EditBox(font, ox + t * 56, top + 66, 46, 16, Component.literal(Gamble.TIERS[t] + " odds")));
			box.setFilter(s -> s.matches("[0-9]{0,6}(\\.[0-9]{0,4})?"));
			box.setValue(oddsText[t]);
			box.setTextColor(Gamble.TIER_COLORS[t]);
			box.setResponder(v -> oddsText[index] = v);
		}
		BubbleScreen.FlatButton save = addRenderableWidget(new BubbleScreen.FlatButton(ox + 5 * 56, top + 66, W - 14 - (ox + 5 * 56 - left), 16,
				Component.literal("Save odds"), () -> {
					List<Float> odds = new ArrayList<>();
					for (String s : oddsText) {
						try {
							odds.add(Float.parseFloat(s.isBlank() ? "0" : s));
						} catch (NumberFormatException e) {
							odds.add(0f);
						}
					}
					ClientPlayNetworking.send(new Gamble.CaseEdit(Gamble.CASE_SET_ODDS, c.id(), 0, 0, odds));
				}));
		save.color = ACCENT;
	}

	private int[] slotAt(double mx, double my, int y0, int rows, int count) {
		for (int i = 0; i < count && i / COLS < rows; i++) {
			int x = gridX() + (i % COLS) * SLOT;
			int y = y0 + (i / COLS) * SLOT;
			if (mx >= x && mx < x + SLOT - 2 && my >= y && my < y + SLOT - 2) {
				return new int[]{i};
			}
		}
		return null;
	}

	/** The player's inventory in the order shown: main inventory (9..35) then the hotbar (0..8). */
	private static int invSlot(int shown) {
		return shown < 27 ? shown + 9 : shown - 27;
	}

	@Override
	protected boolean clicked(double mx, double my, int button) {
		if (super.clicked(mx, my, button)) {
			return true;
		}
		Gamble.CaseInfo c = current();
		if (c == null || minecraft == null || minecraft.player == null) {
			return false;
		}
		List<ItemStack> items = c.tiers().get(tier);
		int[] hit = slotAt(mx, my, gridY(), GRID_ROWS, Math.max(0, items.size() - scroll * COLS));
		if (hit != null) {
			int index = hit[0] + scroll * COLS;
			long now = Util.getMillis();
			if (armed == index && now - armedAt < 3000) {
				ClientPlayNetworking.send(new Gamble.CaseEdit(Gamble.CASE_REMOVE, c.id(), tier, index, List.of()));
				armed = -1;
			} else {
				armed = index;
				armedAt = now;
			}
			return true;
		}
		int[] inv = slotAt(mx, my, invY(), 2, 36);
		if (inv != null) {
			int slot = invSlot(inv[0]);
			if (!minecraft.player.getInventory().getItem(slot).isEmpty()) {
				ClientPlayNetworking.send(new Gamble.CaseEdit(Gamble.CASE_ADD_SLOT, c.id(), tier, slot, List.of()));
			}
			return true;
		}
		return false;
	}

	@Override
	protected boolean scrolled(double mx, double my, double h, double v) {
		Gamble.CaseInfo c = current();
		if (c != null && my >= gridY() && my < gridY() + GRID_ROWS * SLOT) {
			int rows = (c.tiers().get(tier).size() + COLS - 1) / COLS;
			scroll = Math.max(0, Math.min(Math.max(0, rows - GRID_ROWS), scroll - (int) Math.signum(v)));
			return true;
		}
		return super.scrolled(mx, my, h, v);
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

	@Override
	public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
		super.renderBackground(ctx, mouseX, mouseY, delta);
		ctx.fill(0, 0, width, height, 0x55000000);
		panel(ctx, left, top, W, H);
		BubbleScreen.bigText(ctx, font, "Edit cases", left + 20, top + 14);
		Gamble.CaseInfo c = current();
		if (c == null) {
			ctx.drawString(font, "Loading...", left + 20, top + 60, MUTED, false);
			return;
		}
		ctx.drawString(font, "ODDS", left + 20, top + 70, ACCENT, false);
		double total = c.odds().stream().mapToDouble(Float::doubleValue).sum();
		ctx.drawString(font, "Weights per tier; the tabs show each as a share of the total (" + fmt(total) + ").",
				left + 20, top + 88, MUTED, false);

		List<ItemStack> items = c.tiers().get(tier);
		String head = Gamble.TIERS[tier].toUpperCase() + "  ·  " + items.size() + " item" + (items.size() == 1 ? "" : "s") + "  ·  click twice to remove";
		ctx.drawString(font, head, gridX(), gridY() - 11, Gamble.TIER_COLORS[tier], false);
		ItemStack hovered = null;
		boolean hoveredInv = false;
		for (int k = 0; k < COLS * GRID_ROWS; k++) {
			int i = k + scroll * COLS;
			int x = gridX() + (k % COLS) * SLOT;
			int y = gridY() + (k / COLS) * SLOT;
			boolean has = i < items.size();
			boolean hover = has && mouseX >= x && mouseX < x + SLOT - 2 && mouseY >= y && mouseY < y + SLOT - 2;
			boolean isArmed = has && i == armed && Util.getMillis() - armedAt < 3000;
			roundRect(ctx, x, y, SLOT - 2, SLOT - 2, 3, isArmed ? 0xFF5A2A2A : hover ? CARD_HOVER : CARD);
			if (has) {
				ctx.renderItem(items.get(i), x + 2, y + 2);
				ctx.renderItemDecorations(font, items.get(i), x + 2, y + 2);
				if (hover) {
					hovered = items.get(i);
				}
			}
		}
		int rows = (items.size() + COLS - 1) / COLS;
		if (rows > GRID_ROWS) {
			String more = "scroll (" + (scroll + 1) + "/" + (rows - GRID_ROWS + 1) + ")";
			ctx.drawString(font, more, gridX() + COLS * SLOT - font.width(more), gridY() - 11, MUTED, false);
		}

		ctx.drawString(font, "YOUR INVENTORY  ·  click an item to add it to " + Gamble.TIERS[tier], gridX(), invY() - 11, ACCENT, false);
		if (minecraft != null && minecraft.player != null) {
			for (int k = 0; k < 36; k++) {
				int x = gridX() + (k % COLS) * SLOT;
				int y = invY() + (k / COLS) * SLOT;
				ItemStack s = minecraft.player.getInventory().getItem(invSlot(k));
				boolean hover = mouseX >= x && mouseX < x + SLOT - 2 && mouseY >= y && mouseY < y + SLOT - 2;
				roundRect(ctx, x, y, SLOT - 2, SLOT - 2, 3, hover && !s.isEmpty() ? 0xFF2A3A31 : 0xFF16191E);
				if (!s.isEmpty()) {
					ctx.renderItem(s, x + 2, y + 2);
					ctx.renderItemDecorations(font, s, x + 2, y + 2);
					if (hover) {
						hovered = s;
						hoveredInv = true;
					}
				}
			}
		}
		ctx.drawString(font, "Saved to config/bubble-cases.json; the next case opened uses it.", left + 20, top + H - 24, MUTED, false);
		if (hovered != null) {
			List<Component> tip = new ArrayList<>(getTooltipFromItem(minecraft, hovered));
			tip.add(Component.literal(hoveredInv ? "Click: add to " + Gamble.TIERS[tier] : "Click twice: remove").withStyle(hoveredInv ? ChatFormatting.GREEN : ChatFormatting.RED));
			ctx.renderComponentTooltip(font, tip, mouseX, mouseY);
		}
	}
}
