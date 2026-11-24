package moonahmc.bubble.client;

import moonahmc.bubble.CurrencyAdmin;
import moonahmc.bubble.Matter;
import moonahmc.bubble.client.net.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

import static moonahmc.bubble.client.BubbleScreen.CARD;
import static moonahmc.bubble.client.BubbleScreen.MUTED;
import static moonahmc.bubble.client.BubbleScreen.TEXT;
import static moonahmc.bubble.client.BubbleScreen.panel;
import static moonahmc.bubble.client.BubbleScreen.roundRect;

/**
 * Ops (Bubble menu > Admin > Currencies): everyone's Strange Matter and Eidolons, online or not. Type an amount, then
 * + gives it, - takes it, = sets it, for either currency on any row. The server announces each change.
 */
final class CurrencyAdminScreen extends ScaledScreen {
	private static final int W = 470;
	private static final int H = 330;
	private static final int ROW_H = 24;
	private static final int MATTER_COLOR = 0xFF55E0E8;
	private static final int EIDOLON_COLOR = 0xFFD36BFF;
	static List<CurrencyAdmin.Row> rows = List.of();

	private final Screen parent;
	private int left;
	private int top;
	private int scroll;
	private EditBox amount;
	private String amountText = "100";

	CurrencyAdminScreen(Screen parent) {
		super(Component.literal("Currencies"));
		this.parent = parent;
		ClientPlayNetworking.send(new CurrencyAdmin.Edit("request", new java.util.UUID(0, 0), "", 0));
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

	private int listTop() {
		return top + 84;
	}

	private int visibleRows() {
		return (top + H - 40 - listTop()) / ROW_H;
	}

	private double amount() {
		try {
			return Math.max(0, Double.parseDouble(amountText.trim().replace(",", "")));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	@Override
	protected void init() {
		fit();
		left = (width - W) / 2;
		top = (height - H) / 2;
		amount = addRenderableWidget(new EditBox(font, left + 84, top + 40, 90, 16, Component.literal("Amount")));
		amount.setValue(amountText);
		amount.setFilter(s -> s.matches("[0-9.,]*"));
		amount.setResponder(s -> amountText = s);
		amount.setMaxLength(12);
		int[] quick = {10, 100, 1000};
		for (int i = 0; i < quick.length; i++) {
			int v = quick[i];
			BubbleScreen.FlatButton b = addRenderableWidget(new BubbleScreen.FlatButton(left + 182 + i * 46, top + 40, 42, 16,
					Component.literal(String.valueOf(v)), () -> {
				amountText = String.valueOf(v);
				amount.setValue(amountText);
			}));
			b.ghost = true;
		}
		scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visibleRows())));
		for (int i = 0; i < visibleRows() && i + scroll < rows.size(); i++) {
			CurrencyAdmin.Row r = rows.get(i + scroll);
			int y = listTop() + i * ROW_H;
			button(r, CurrencyAdmin.MATTER, "give", "+", left + 236, y, MATTER_COLOR);
			button(r, CurrencyAdmin.MATTER, "take", "-", left + 256, y, MATTER_COLOR);
			button(r, CurrencyAdmin.MATTER, "set", "=", left + 276, y, MATTER_COLOR);
			button(r, CurrencyAdmin.EIDOLONS, "give", "+", left + 386, y, EIDOLON_COLOR);
			button(r, CurrencyAdmin.EIDOLONS, "take", "-", left + 406, y, EIDOLON_COLOR);
			button(r, CurrencyAdmin.EIDOLONS, "set", "=", left + 426, y, EIDOLON_COLOR);
		}
		addRenderableWidget(new BubbleScreen.FlatButton(left + W - 14 - 64, top + H - 30, 64, 20, Component.literal("Back"), this::onClose)).ghost = true;
	}

	private void button(CurrencyAdmin.Row r, String currency, String action, String label, int x, int y, int color) {
		BubbleScreen.FlatButton b = addRenderableWidget(new BubbleScreen.FlatButton(x, y + 3, 18, 16, Component.literal(label), () -> {
			double a = amount();
			if (a < 0) {
				return;
			}
			ClientPlayNetworking.send(new CurrencyAdmin.Edit(action, r.id(), currency, a));
		}));
		b.color = color;
		String what = currency.equals(CurrencyAdmin.MATTER) ? "Strange Matter" : "Eidolons";
		String verb = switch (action) {
			case "give" -> "Give " + r.name() + " the amount of " + what;
			case "take" -> "Take the amount of " + what + " from " + r.name();
			default -> "Set " + r.name() + "'s " + what + " to the amount";
		};
		b.setTooltip(Tooltip.create(Component.literal(verb)));
	}

	@Override
	protected boolean scrolled(double x, double y, double h, double v) {
		int max = Math.max(0, rows.size() - visibleRows());
		int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(v)));
		if (next != scroll) {
			scroll = next;
			rebuildWidgets();
		}
		return true;
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
		BubbleScreen.bigText(ctx, font, "Currencies", left + 22, top + 14);
		ctx.drawString(font, "Amount", left + 24, top + 44, TEXT, false);
		ctx.drawString(font, "PLAYER", left + 24, top + 70, MUTED, false);
		ctx.drawString(font, "STRANGE MATTER (this world)", left + 150, top + 70, MATTER_COLOR, false);
		ctx.drawString(font, "EIDOLONS (forever)", left + 324, top + 70, EIDOLON_COLOR, false);
		if (rows.isEmpty()) {
			ctx.drawString(font, "Loading...", left + 24, listTop() + 6, MUTED, false);
		}
		for (int i = 0; i < visibleRows() && i + scroll < rows.size(); i++) {
			CurrencyAdmin.Row r = rows.get(i + scroll);
			int y = listTop() + i * ROW_H;
			roundRect(ctx, left + 14, y, W - 28, ROW_H - 2, 5, CARD);
			ctx.fill(left + 22, y + 9, left + 26, y + 13, r.online() ? 0xFF6EE08A : 0xFF5A606B);
			ctx.drawString(font, font.plainSubstrByWidth(r.name(), 110), left + 32, y + 7, r.online() ? TEXT : MUTED, false);
			String m = Matter.format(r.matter());
			ctx.drawString(font, m, left + 230 - font.width(m), y + 7, MATTER_COLOR, false);
			String e = String.format("%,d", r.eidolons());
			ctx.drawString(font, e, left + 380 - font.width(e), y + 7, EIDOLON_COLOR, false);
		}
		if (rows.size() > visibleRows()) {
			String more = (scroll + 1) + "-" + Math.min(rows.size(), scroll + visibleRows()) + " of " + rows.size() + " (scroll)";
			ctx.drawString(font, more, left + 24, top + H - 24, MUTED, false);
		}
	}
}
