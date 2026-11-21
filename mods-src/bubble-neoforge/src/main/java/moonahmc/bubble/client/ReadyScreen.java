package moonahmc.bubble.client;

import moonahmc.bubble.BubbleServer;
import moonahmc.bubble.Lobby;
import moonahmc.bubble.client.net.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import static moonahmc.bubble.client.BubbleScreen.ACCENT;
import static moonahmc.bubble.client.BubbleScreen.CARD;
import static moonahmc.bubble.client.BubbleScreen.MUTED;
import static moonahmc.bubble.client.BubbleScreen.TEXT;
import static moonahmc.bubble.client.BubbleScreen.panel;
import static moonahmc.bubble.client.BubbleScreen.roundRect;

/** The ready-up screen at the start of a run: who's here, who's ready, and (for ops) Start now. */
public final class ReadyScreen extends ScaledScreen {
	private static final int W = 320;
	private static final int H = 330;
	private static final long[] STAKES = {1, 5, 10, 25, 50};
	private static int betOption;
	private static int betStake = 1;
	private int left;
	private int top;

	ReadyScreen() {
		super(Component.literal("Waiting for players"));
	}

	@Override
	protected int panelWidth() {
		return W;
	}

	@Override
	protected int panelHeight() {
		return H;
	}

	private static boolean meReady() {
		Lobby.State s = ClientState.lobby;
		Minecraft mc = Minecraft.getInstance();
		if (s == null || mc.player == null) {
			return false;
		}
		int i = s.names().indexOf(mc.player.getGameProfile().getName());
		return i >= 0 && s.ready().get(i);
	}

	@Override
	protected void init() {
		fit();
		left = (width - W) / 2;
		top = (height - H) / 2;
		Lobby.State s = ClientState.lobby;
		boolean ready = meReady();
		BubbleScreen.FlatButton readyButton = addRenderableWidget(new BubbleScreen.FlatButton(left + 14, top + H - 34, 120, 20,
				Component.literal(ready ? "Not ready" : "I'm ready"), () -> ClientPlayNetworking.send(new Lobby.Ready(!meReady()))));
		readyButton.ghost = ready;
		if (s != null && s.canStart()) {
			BubbleScreen.FlatButton start = addRenderableWidget(new BubbleScreen.FlatButton(left + 140, top + H - 34, 96, 20,
					Component.literal("Start now"), () -> ClientPlayNetworking.send(new Lobby.Admin(BubbleServer.ADMIN_START))));
			start.color = 0xFFF2C14E;
		}
		addRenderableWidget(new BubbleScreen.FlatButton(left + W - 14 - 60, top + H - 34, 60, 20, Component.literal("Close"), this::onClose)).ghost = true;
		// Betting (Eidolons): who ends this run?
		moonahmc.bubble.Eidolons.Bets bets = ClientState.bets;
		if (bets != null && bets.open() && !bets.keys().isEmpty()) {
			betOption = Math.max(0, Math.min(bets.keys().size() - 1, betOption));
			String[] labels = bets.labels().toArray(new String[0]);
			String[] stakes = new String[STAKES.length];
			for (int i = 0; i < STAKES.length; i++) {
				stakes[i] = STAKES[i] + " Eidolons";
			}
			int by = top + H - 70;
			addRenderableWidget(new BubbleScreen.Choice(left + 14, by, 150, 16, labels, () -> betOption, v -> betOption = v));
			addRenderableWidget(new BubbleScreen.Choice(left + 168, by, 96, 16, stakes, () -> betStake, v -> betStake = v));
			BubbleScreen.FlatButton place = addRenderableWidget(new BubbleScreen.FlatButton(left + 268, by, 38, 16, Component.literal("Bet"),
					() -> ClientPlayNetworking.send(new moonahmc.bubble.Eidolons.PlaceBet(bets.keys().get(betOption), STAKES[betStake]))));
			place.color = 0xFFD36BFF;
			place.active = bets.eidolons() + bets.myAmount() >= STAKES[betStake];
		}
	}

	/** New lobby state from the server. */
	void refresh() {
		if (ClientState.lobby == null || !ClientState.lobby.active()) {
			onClose();
		} else {
			rebuildWidgets();
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
		BubbleScreen.bigText(ctx, font, "Waiting for players", left + 20, top + 16);
		Lobby.State s = ClientState.lobby;
		if (s == null) {
			return;
		}
		long readyCount = s.ready().stream().filter(b -> b).count();
		String status = s.countdown() >= 0 ? "Starting in " + s.countdown() + "..." : readyCount + " of " + s.names().size() + " ready";
		ctx.drawString(font, status, left + 20, top + 38, s.countdown() >= 0 ? 0xFFF2C14E : ACCENT, false);
		// What this run is: mode, difficulty, wheel, events, plus the bubble rules everyone plays by
		java.util.List<String> info = new java.util.ArrayList<>();
		if (s.info() != null && !s.info().isEmpty()) {
			info.addAll(java.util.Arrays.asList(s.info().split("\n")));
		}
		if (ClientState.hardcoreReset) {
			info.add("Hardcore reset");
		}
		if (ClientState.soulLink) {
			info.add("Soul link");
		}
		int iy = top + 52;
		int ix = left + 20;
		for (String part : info) {
			int pw = font.width(part) + 10;
			if (ix + pw > left + W - 20) {
				ix = left + 20;
				iy += 14;
			}
			roundRect(ctx, ix, iy, pw, 12, 4, 0xFF20262D);
			ctx.drawString(font, part, ix + 5, iy + 2, part.equals("Hardcore reset") ? BubbleScreen.DANGER : part.equals("Soul link") ? BubbleScreen.SOUL : TEXT, false);
			ix += pw + 4;
		}
		int listTop = (info.isEmpty() ? top + 50 : iy + 18);
		ctx.drawString(font, "Everyone presses Ready to start. You can't get hurt while waiting.", left + 20, listTop, MUTED, false);
		listTop += 14;
		moonahmc.bubble.Eidolons.Bets bets = ClientState.bets;
		int listBottom = bets != null && bets.open() ? top + H - 70 - 30 : top + H - 40;
		int maxRows = Math.max(1, (listBottom - listTop) / 16);
		int shown = Math.min(s.names().size(), maxRows * 2);
		if (shown < s.names().size()) {
			shown = Math.max(0, shown - 1);
		}
		int colW = (W - 40) / 2;
		Minecraft mc = Minecraft.getInstance();
		for (int i = 0; i < shown; i++) {
			int x = left + 20 + (i % 2) * colW;
			int y = listTop + (i / 2) * 16;
			boolean r = s.ready().get(i);
			roundRect(ctx, x, y, colW - 6, 14, 4, CARD);
			ctx.drawString(font, r ? "✔" : "…", x + 5, y + 3, r ? 0xFF6EE08A : MUTED, false);
			var who = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(s.names().get(i));
			int nx = x + 16;
			if (who != null) {
				net.minecraft.client.gui.components.PlayerFaceRenderer.draw(ctx, who.getSkin(), nx, y + 3, 8);
				nx += 11;
			}
			ctx.drawString(font, font.plainSubstrByWidth(s.names().get(i), colW - 22 - (nx - x)), nx, y + 3, r ? TEXT : MUTED, false);
		}
		if (shown < s.names().size()) {
			int i = shown;
			ctx.drawString(font, "+" + (s.names().size() - shown) + " more", left + 20 + (i % 2) * colW + 6, listTop + (i / 2) * 16 + 3, MUTED, false);
		}
		if (bets != null && bets.open()) {
			int by = top + H - 70;
			ctx.drawString(font, "BET EIDOLONS: who ends this run?", left + 14, by - 26, 0xFFD36BFF, false);
			StringBuilder pots = new StringBuilder();
			for (int i = 0; i < bets.keys().size(); i++) {
				if (bets.pots().get(i) > 0) {
					pots.append(pots.length() > 0 ? "  \u00B7  " : "Pot: ").append(bets.labels().get(i)).append(" ").append(bets.pots().get(i));
				}
			}
			String mine = bets.myAmount() > 0 ? "Your bet: " + bets.myAmount() + " on " + bets.labels().get(Math.max(0, bets.keys().indexOf(bets.myOption())))
					: "You have " + bets.eidolons() + " Eidolons. Winners split the pot; if nobody calls it, everyone gets theirs back.";
			ctx.drawString(font, font.plainSubstrByWidth(mine, W - 28), left + 14, by - 14, MUTED, false);
			if (pots.length() > 0) {
				ctx.drawString(font, font.plainSubstrByWidth(pots.toString(), W - 28), left + 14, by + 20, 0xFFF2C14E, false);
			}
		}
	}

	/** Little sounds: someone readied up, the countdown started, and the last three seconds. */
	static void sounds(Lobby.State before, Lobby.State now) {
		if (now == null || !now.active() || before == null || !before.active()) {
			return;
		}
		var sm = Minecraft.getInstance().getSoundManager();
		long was = before.ready().stream().filter(b -> b).count();
		long is = now.ready().stream().filter(b -> b).count();
		if (before.countdown() < 0 && now.countdown() >= 0) {
			sm.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(), 1.0f, 0.7f));
		} else if (now.countdown() >= 1 && now.countdown() <= 3 && now.countdown() != before.countdown()) {
			sm.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1.6f, 0.5f));
		} else if (is > was) {
			sm.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_CHIME.value(), 1.4f, 0.45f));
		}
	}

	/** One line at the top of the screen while the lobby is waiting and no screen is open. */
	static void renderHud(GuiGraphics ctx) {
		Lobby.State s = ClientState.lobby;
		Minecraft mc = Minecraft.getInstance();
		if (s == null || !s.active() || mc.screen != null || mc.options.hideGui) {
			return;
		}
		long readyCount = s.ready().stream().filter(b -> b).count();
		String text = s.countdown() >= 0 ? "Starting in " + s.countdown() + "..."
				: "Waiting for players: " + readyCount + "/" + s.names().size() + " ready  ·  press " + BubbleClient.openKeyName() + " to ready up";
		int w = mc.font.width(text) + 16;
		int x = (ctx.guiWidth() - w) / 2;
		roundRect(ctx, x, 6, w, 16, 6, 0xC0121418);
		ctx.drawString(mc.font, text, x + 8, 10, s.countdown() >= 0 ? 0xFFF2C14E : TEXT, false);
	}
}
