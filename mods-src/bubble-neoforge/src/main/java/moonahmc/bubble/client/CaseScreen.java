package moonahmc.bubble.client;

import moonahmc.bubble.Gamble;
import moonahmc.bubble.Matter;
import moonahmc.bubble.ShopPayloads;
import moonahmc.bubble.client.net.ClientPlayNetworking;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import static moonahmc.bubble.client.BubbleScreen.ACCENT;
import static moonahmc.bubble.client.BubbleScreen.MUTED;
import static moonahmc.bubble.client.BubbleScreen.TEXT;
import static moonahmc.bubble.client.BubbleScreen.panel;
import static moonahmc.bubble.client.BubbleScreen.roundRect;

/**
 * Case opening: one big reel, or five smaller ones that stop one after another. The server already decided what you
 * won; this just shows it. Speed up makes the reels stop right away (and the server hands the items over at once).
 */
public final class CaseScreen extends ScaledScreen {
	private static final int W = 440;
	private static final long FAST_MS = 450;

	private final Gamble.Roll roll;
	private final Screen parent;
	private final int n;
	private final int card;
	private final int cardH;
	private final float itemScale;
	private final long startMs;
	private final long spinMs;
	private final float[] landing;
	private final int[] lastCard;
	private final boolean[] done;
	private long fastAt = -1;
	private double[] fastFrom;
	private int left;
	private int top;
	private boolean finished;
	private BubbleScreen.FlatButton again1;
	private BubbleScreen.FlatButton again5;
	private BubbleScreen.FlatButton speed;

	CaseScreen(Gamble.Roll roll, Screen parent) {
		super(Component.literal(roll.caseName()));
		this.roll = roll;
		this.parent = parent;
		this.n = Math.max(1, roll.reels().size());
		this.card = n == 1 ? 64 : 48;
		this.cardH = n == 1 ? 78 : 50;
		this.itemScale = n == 1 ? 2f : 1.5f;
		this.startMs = Util.getMillis();
		this.spinMs = Gamble.SPIN_TICKS * 50L - 500;
		this.landing = new float[n];
		this.lastCard = new int[n];
		this.done = new boolean[n];
		for (int i = 0; i < n; i++) {
			landing[i] = (float) ((Math.random() - 0.5) * (card - 18));
			lastCard[i] = -1;
		}
	}

	Screen parent() {
		return parent;
	}

	private int panelH() {
		return 132 + n * (cardH + 10) + (n == 1 ? 26 : 0);
	}

	@Override
	protected int panelWidth() {
		return W;
	}

	@Override
	protected int panelHeight() {
		return panelH();
	}

	@Override
	protected void init() {
		fit();
		left = (width - W) / 2;
		top = (height - panelH()) / 2;
		int by = top + panelH() - 32;
		again1 = addRenderableWidget(new BubbleScreen.FlatButton(left + 14, by, 120, 20,
				Component.literal("Open 1 (" + roll.price() + ")"), () -> open(1)));
		again5 = addRenderableWidget(new BubbleScreen.FlatButton(left + 140, by, 130, 20,
				Component.literal("Open 5 (" + roll.price() * 5 + ")"), () -> open(5)));
		speed = addRenderableWidget(new BubbleScreen.FlatButton(left + 14, by, 120, 20, Component.literal("▶▶ Speed up"), this::speedUp));
		speed.color = 0xFFF2C14E;
		addRenderableWidget(new BubbleScreen.FlatButton(left + W - 14 - 110, by, 110, 20, Component.literal(fromShop() ? "Back to shop" : "Close"), this::onClose)).ghost = true;
		updateButtons();
	}

	private void updateButtons() {
		again1.visible = finished && fromShop();
		again5.visible = finished && fromShop();
		speed.visible = !finished;
	}

	/** Opened from the shop (so "open again" can buy another), not from a case item. */
	private boolean fromShop() {
		return roll.section() >= 0;
	}

	private void open(int count) {
		if (finished) {
			ClientPlayNetworking.send(new ShopPayloads.Buy(roll.section(), roll.index(), count));
		}
	}

	private void speedUp() {
		if (fastAt >= 0 || finished) {
			return;
		}
		fastFrom = new double[n];
		for (int i = 0; i < n; i++) {
			fastFrom[i] = progress(i);
		}
		fastAt = Util.getMillis();
		ClientPlayNetworking.send(new Gamble.Skip());
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

	/** 0..1 for reel i; with Speed up, whatever was left plays out in under half a second. */
	private double progress(int i) {
		long reelMs = spinMs + i * Gamble.STAGGER_TICKS * 50L;
		double normal = Mth.clamp((Util.getMillis() - startMs) / (double) reelMs, 0, 1);
		if (fastAt < 0) {
			return normal;
		}
		double f = Mth.clamp((Util.getMillis() - fastAt) / (double) FAST_MS, 0, 1);
		return fastFrom[i] + (1 - fastFrom[i]) * f;
	}

	private double scroll(int i, double t) {
		double end = roll.reels().get(i).winner() * card + card / 2.0 + landing[i];
		return end * (1 - Math.pow(1 - t, 4));
	}

	private int reelY(int i) {
		return top + 58 + i * (cardH + 10);
	}

	@Override
	protected void renderScaled(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
		super.renderScaled(ctx, mouseX, mouseY, delta);
		int reelL = left + 14;
		int reelR = left + W - 14;
		int center = left + W / 2;
		boolean allDone = true;
		for (int r = 0; r < n; r++) {
			Gamble.Reel reel = roll.reels().get(r);
			double t = progress(r);
			double pos = scroll(r, t);
			int y = reelY(r);
			int under = (int) Math.floor(pos / card);
			if (under != lastCard[r] && t < 1 && r == firstSpinning()) {
				minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.9f, 0.16f));
			}
			lastCard[r] = under;
			if (t >= 1 && !done[r]) {
				done[r] = true;
				int tier = reel.tiers().get(reel.winner());
				minecraft.getSoundManager().play(SimpleSoundInstance.forUI(tier >= 3 ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.PLAYER_LEVELUP,
						1.0f + r * 0.05f, tier >= 3 ? 0.8f : 0.45f));
			}
			allDone &= done[r];

			ctx.enableScissor(reelL, y - 3, reelR, y + cardH + 3);
			roundRect(ctx, reelL, y - 3, reelR - reelL, cardH + 6, 6, 0xFF0E1014);
			for (int i = 0; i < reel.items().size(); i++) {
				int x = (int) Math.round(center + i * card - pos);
				if (x + card < reelL || x > reelR) {
					continue;
				}
				int tier = reel.tiers().get(i);
				int color = Gamble.TIER_COLORS[tier];
				boolean win = done[r] && i == reel.winner();
				int cw = card - 4;
				roundRect(ctx, x, y, cw, cardH, 5, win ? 0xFF2A3038 : 0xFF1A1E25);
				ctx.fillGradient(x + 2, y + cardH - cardH / 3, x + cw - 2, y + cardH - 3, color & 0x00FFFFFF, (color & 0x00FFFFFF) | 0x55000000);
				ctx.fill(x + 2, y + cardH - 3, x + cw - 2, y + cardH - 1, color);
				ItemStack stack = reel.items().get(i);
				ctx.pose().pushPose();
				ctx.pose().translate(x + cw / 2f - 8 * itemScale, y + (cardH - 16 * itemScale) / 2f - 3, 0);
				ctx.pose().scale(itemScale, itemScale, 1f);
				ctx.renderItem(stack, 0, 0);
				ctx.renderItemDecorations(font, stack, 0, 0);
				ctx.pose().popPose();
				if (win) {
					int pulse = (int) (120 + 100 * Math.sin(Util.getMillis() / 120.0));
					ctx.renderOutline(x, y, cw, cardH, (pulse << 24) | (color & 0xFFFFFF));
				}
			}
			ctx.disableScissor();
			ctx.fill(center - 1, y - 6, center + 1, y + cardH + 6, 0xFFF2C14E);

			// hover a stopped card to see what it is
			if (done[r] && mouseY >= y && mouseY < y + cardH && mouseX >= reelL && mouseX < reelR) {
				int i = (int) Math.floor((mouseX - center + pos) / card);
				if (i >= 0 && i < reel.items().size()) {
					ctx.renderTooltip(font, reel.items().get(i), mouseX, mouseY);
				}
			}
		}
		if (allDone && !finished) {
			finished = true;
			updateButtons();
		}

		// What you won
		if (finished) {
			int y = reelY(n - 1) + cardH + 12;
			if (n == 1) {
				Gamble.Reel reel = roll.reels().get(0);
				ItemStack won = reel.items().get(reel.winner());
				int tier = reel.tiers().get(reel.winner());
				Component line = Component.empty().append(won.getHoverName().copy().withStyle(s -> s.withColor(Gamble.TIER_COLORS[tier] & 0xFFFFFF)))
						.append(won.getCount() > 1 ? "  ×" + won.getCount() : "");
				ctx.pose().pushPose();
				ctx.pose().translate(center, y, 0);
				ctx.pose().scale(1.4f, 1.4f, 1f);
				ctx.drawString(font, line, -font.width(line) / 2, 0, TEXT, true);
				ctx.pose().popPose();
				String tierText = Gamble.TIERS[tier];
				ctx.drawString(font, tierText, center - font.width(tierText) / 2, y + 16, Gamble.TIER_COLORS[tier], false);
			} else {
				Component line = Component.literal("You won: ").withStyle(s -> s.withColor(MUTED & 0xFFFFFF));
				for (int r = 0; r < n; r++) {
					Gamble.Reel reel = roll.reels().get(r);
					int tier = reel.tiers().get(reel.winner());
					ItemStack won = reel.items().get(reel.winner());
					if (r > 0) {
						line = line.copy().append(Component.literal(", ").withStyle(s -> s.withColor(MUTED & 0xFFFFFF)));
					}
					line = line.copy().append(won.getHoverName().copy().withStyle(s -> s.withColor(Gamble.TIER_COLORS[tier] & 0xFFFFFF)));
				}
				var lines = font.split(line, W - 40);
				for (int i = 0; i < Math.min(2, lines.size()); i++) {
					ctx.drawString(font, lines.get(i), left + 20, y + i * 10, TEXT, false);
				}
			}
		}
	}

	private int firstSpinning() {
		for (int r = 0; r < n; r++) {
			if (!done[r]) {
				return r;
			}
		}
		return -1;
	}

	@Override
	public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
		super.renderBackground(ctx, mouseX, mouseY, delta);
		ctx.fill(0, 0, width, height, 0x55000000);
		panel(ctx, left, top, W, panelH());
		BubbleScreen.bigText(ctx, font, n > 1 ? roll.caseName() + " ×" + n : roll.caseName(), left + 22, top + 16);
		String bal = "Strange Matter: " + Matter.format(ClientState.balance);
		ctx.drawString(font, bal, left + W - 22 - font.width(bal), top + 16, ACCENT, false);
		String luck;
		int luckColor;
		if (roll.luck() > 0.01f) {
			luck = String.format("Luck +%.1f: rarer items more likely", roll.luck());
			luckColor = 0xFF6EE08A;
		} else if (roll.luck() < -0.01f) {
			luck = String.format("Luck %.1f: rarer items less likely", roll.luck());
			luckColor = 0xFFEF7D7D;
		} else {
			luck = "No luck bonus (Luck potions and gear raise the odds)";
			luckColor = MUTED;
		}
		ctx.drawString(font, luck, left + W - 22 - font.width(luck), top + 30, luckColor, false);
		String hint = finished ? "Your items are in your inventory." : "Spinning...";
		ctx.drawString(font, hint, left + 22, top + 38, MUTED, false);
	}
}
