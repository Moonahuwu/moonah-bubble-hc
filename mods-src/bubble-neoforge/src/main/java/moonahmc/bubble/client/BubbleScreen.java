package moonahmc.bubble.client;

import moonahmc.bubble.Payloads;
import moonahmc.bubble.Pregame;
import moonahmc.bubble.PregamePayloads;
import net.minecraft.client.gui.components.EditBox;
import java.util.ArrayList;
import moonahmc.bubble.client.net.ClientPlayNetworking;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** A clean, dark settings panel in the style of modern client menus. Ops can edit; everyone else can look. */
public final class BubbleScreen extends ScaledScreen {
	@Override
	protected int panelWidth() {
		return W;
	}

	/** As tall as this tab needs, but never taller than the window: the middle scrolls instead of the panel shrinking. */
	@Override
	protected int panelHeight() {
		return Math.min(contentHeight(), Math.max(MIN_H, realHeight() - 16));
	}

	/** The full height this tab would like (header, everything in it, footer). */
	private int contentHeight() {
		return switch (tab) {
			case 1 -> 62 + 2 * PRE_HEAD + 13 * PRE_ROW + 16 + Math.max(1, pGroups.size()) * STRUCT_ROW + 40;
			case 2 -> adminHeight();
			default -> 60 + 2 * (CARD_H + GAP) + 70 + GAP + 64 + GAP + 44 + 44;
		};
	}

	private static final int W = 400;
	private static final int MIN_H = 240;
	/** Set in init from panelHeight(). */
	private int H = 554;
	/** How far the middle of the panel is scrolled (pixels). */
	private int contentScroll;
	/** Widgets that stay put when the middle scrolls (tab buttons, Done). */
	private final java.util.Set<AbstractWidget> fixed = new java.util.HashSet<>();

	private int viewTop() {
		return top + 56;
	}

	private int viewBottom() {
		return top + H - 36;
	}

	private int maxScroll() {
		return Math.max(0, contentHeight() - H);
	}
	private static final int MIN = 2;
	private static final int MAX = 500;
	private static final int GAP = 8;
	private static final int CARD_H = 56;

	static final int BG = 0xF2121418;
	static final int CARD = 0xFF1A1E25;
	static final int CARD_HOVER = 0xFF20252D;
	static final int ACCENT = 0xFF7CC4A4;
	static final int TEXT = 0xFFE6E9EE;
	static final int MUTED = 0xFF8B95A3;
	static final int OFF = 0xFF3A3F48;
	static final int DANGER = 0xFFEF7D7D;
	static final int SOUL = 0xFFF28BB0;

	private final Screen parent;
	private int left;
	private int top;
	private int cardW;
	private boolean bubble;
	private boolean push;
	private boolean wrap;
	private boolean soulLink;
	private boolean hardcore;
	private boolean vertical;
	private int radius;
	private Toggle bubbleToggle;
	private Toggle soulToggle;
	private Toggle hardcoreToggle;
	private Segmented3 edgeSwitch;
	private Segmented shapeSwitch;
	private RadiusSlider slider;

	/** Top of the "wall shows within" card (your own setting). */
	private int wallY() {
		return row(2) + 70 + GAP;
	}

	/** Top of the Revive card (under the wall card). */
	private int reviveY() {
		return wallY() + 64 + GAP;
	}

	private static final int[] REVIVE_SECONDS = {10, 15, 20, 30, 45, 60, 90, 120, 180};
	private static final int[] REVIVE_HOLD = {0, 1, 2, 3, 5, 8};
	/** The revive settings had arrived when the widgets were made (so the card's Edit button is there). */
	private boolean reviveShown;

	private static int nearest(int[] options, int v) {
		int best = 0;
		for (int i = 0; i < options.length; i++) {
			if (Math.abs(options[i] - v) < Math.abs(options[best] - v)) {
				best = i;
			}
		}
		return best;
	}

	private static String[] labels(int[] options, String zero) {
		String[] out = new String[options.length];
		for (int i = 0; i < options.length; i++) {
			out[i] = options[i] == 0 ? zero : options[i] + "s";
		}
		return out;
	}

	// "Next run" tab (pre-game setup)
	private static int tab;
	private int pLucky;
	private int pRainbow;
	private int pNuke;
	private int pSpeed;
	private int pDifficulty;
	private int pGatling;
	private int pWheel;
	private int pLobby;
	private int pWatchers;
	private int pJockeys;
	private int pSwarm;
	private int pMode;
	private List<String> pModes = List.of();
	private String pSeed = "";
	private List<PregamePayloads.Group> pGroups = new ArrayList<>();
	private int structScroll;
	private EditBox seedBox;
	private static final int PRE_ROW = 26;
	private static final int STRUCT_ROW = 20;

	public BubbleScreen(Screen parent) {
		super(Component.literal("Bubble"));
		this.parent = parent;
		loadPregame();
		bubble = ClientState.bubbleEnabled;
		push = ClientState.pushMode;
		wrap = ClientState.wrapMode;
		soulLink = ClientState.soulLink;
		hardcore = ClientState.hardcoreReset;
		vertical = ClientState.vertical;
		radius = ClientState.radius;
	}

	private int col(int i) {
		return left + 14 + i * (cardW + GAP);
	}

	private int row(int i) {
		return top + 60 + i * (CARD_H + GAP) - contentScroll;
	}

	@Override
	protected void init() {
		fixed.clear();
		initPanel();
		// Anything in the scrolling middle that isn't fully in view is hidden (so it can't be clicked under the header).
		for (var c : children()) {
			if (c instanceof AbstractWidget w && !fixed.contains(w)) {
				w.visible = w.getY() >= viewTop() && w.getY() + w.getHeight() <= viewBottom();
			}
		}
	}

	private <T extends AbstractWidget> T fixedWidget(T w) {
		fixed.add(w);
		return addRenderableWidget(w);
	}

	private void initPanel() {
		if (tab == 2 && !ClientState.canEdit) {
			tab = 0;
		}
		if (tab == 1 && ClientState.pregame == null) {
			tab = 0;
		}
		fit();
		H = panelHeight();
		contentScroll = Math.max(0, Math.min(contentScroll, maxScroll()));
		left = (width - W) / 2;
		top = (height - H) / 2;
		cardW = (W - 28 - GAP) / 2;
		if (tab == 2 && !ClientState.canEdit) {
			tab = 0;
		}
		if (ClientState.canEdit) {
			FlatButton adminTab = fixedWidget(new FlatButton(left + W - 22 - 66 - 4 - 62 - 4 - 54, top + 36, 54, 16, Component.literal("Admin"), () -> switchTab(2)));
			adminTab.ghost = tab != 2;
			adminTab.selected = tab == 2;
			adminTab.color = DANGER;
		}
		if (ClientState.pregame != null) {
			FlatButton settingsTab = fixedWidget(new FlatButton(left + W - 22 - 66 - 4 - 62, top + 36, 62, 16, Component.literal("Settings"), () -> switchTab(0)));
			FlatButton nextTab = fixedWidget(new FlatButton(left + W - 22 - 66, top + 36, 66, 16, Component.literal("Next run"), () -> switchTab(1)));
			settingsTab.ghost = tab != 0;
			settingsTab.selected = tab == 0;
			nextTab.ghost = tab != 1;
			nextTab.selected = tab == 1;
		} else if (tab == 1) {
			tab = 0;
		}
		if (tab == 2) {
			initAdmin();
			fixedWidget(new FlatButton(left + W - 88, top + H - 30, 68, 20, Component.literal("Done"), this::onClose));
			return;
		}
		if (tab == 1) {
			initPregame();
			fixedWidget(new FlatButton(left + W - 88, top + H - 30, 68, 20, Component.literal("Done"), this::onClose));
			return;
		}
		bubbleToggle = addRenderableWidget(new Toggle(col(0) + cardW - 46, row(0) + 8, () -> bubble, v -> {
			bubble = v;
			send();
		}));
		soulToggle = addRenderableWidget(new Toggle(col(1) + cardW - 46, row(0) + 8, () -> soulLink, v -> {
			soulLink = v;
			send();
		}, SOUL));
		hardcoreToggle = addRenderableWidget(new Toggle(col(0) + cardW - 46, row(1) + 8, () -> hardcore, v -> {
			hardcore = v;
			send();
		}, DANGER));
		edgeSwitch = addRenderableWidget(new Segmented3(col(1) + cardW - 12 - 114, row(1) + 7, 114, 18, new String[] {"Pull", "Push", "Wrap"},
				() -> wrap ? 2 : push ? 1 : 0, v -> {
					push = v == 1;
					wrap = v == 2;
					send();
				}));
		shapeSwitch = addRenderableWidget(new Segmented(left + W - 28 - 104, row(2) + 6, 104, 18, "Ring", "Sphere", () -> vertical, v -> {
			vertical = v;
			send();
		}));
		slider = addRenderableWidget(new RadiusSlider(left + 28, row(2) + 30, W - 56, 16));
		addRenderableWidget(new WallSlider(left + 28, wallY() + 24, W - 56, 14));
		addRenderableWidget(new Choice(left + W - 28 - 84, wallY() + 43, 84, 16, ClientSettings.DOWN_CAM, () -> ClientSettings.downCam, v -> {
			ClientSettings.downCam = v;
			ClientSettings.save();
		}));
		// Revive: everyone sees how it works; ops get an Edit button (Admin > Rules has all of it)
		reviveShown = ReviveClient.settings != null;
		if (reviveShown && ReviveClient.settings.canEdit()) {
			FlatButton edit = addRenderableWidget(new FlatButton(left + W - 28 - 48, reviveY() + 7, 48, 16, Component.literal("Edit"),
					() -> minecraft.setScreen(new RulesScreen(this))));
			edit.ghost = true;
		}
		fixedWidget(new FlatButton(left + W - 88, top + H - 30, 68, 20, Component.literal("Done"), this::onClose));
		applyPermissions();
	}

	private void applyPermissions() {
		if (bubbleToggle == null || tab != 0) {
			return;
		}
		boolean editable = ClientState.canEdit;
		bubbleToggle.active = editable;
		soulToggle.active = editable;
		hardcoreToggle.active = editable;
		edgeSwitch.active = editable;
		shapeSwitch.active = editable;
		slider.active = editable;
	}

	/** Called when the server sends new settings (someone else changed them, or ours were accepted). */
	void syncFromServer() {
		if (tab == 0 && !reviveShown && ReviveClient.settings != null && bubbleToggle != null) {
			rebuildWidgets(); // the revive settings just arrived: add the Revive card
			return;
		}
		bubble = ClientState.bubbleEnabled;
		push = ClientState.pushMode;
		wrap = ClientState.wrapMode;
		soulLink = ClientState.soulLink;
		hardcore = ClientState.hardcoreReset;
		vertical = ClientState.vertical;
		if (slider != null && !slider.dragging) {
			radius = ClientState.radius;
			slider.setRadius(radius);
		}
		applyPermissions();
	}

	private void send() {
		if (ClientState.canEdit) {
			ClientPlayNetworking.send(new Payloads.Update(bubble, push, soulLink, hardcore, vertical, radius, wrap));
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

	@Override
	public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
		super.renderBackground(ctx, mouseX, mouseY, delta);
		ctx.fill(0, 0, width, height, 0x55000000);
		panel(ctx, left, top, W, H);

		// Header
		bigText(ctx, font, "Bubble", left + 22, top + 18);
		String info = "Run #" + ClientState.run + "  ·  " + ClientState.players + (ClientState.players == 1 ? " player" : " players") + " online";
		ctx.drawString(font, Component.literal(info), left + 22, top + 40, MUTED, false);
		int right = left + W - 22;
		right = pill(ctx, font, right, top + 20, bubble ? "ACTIVE" : "OFF", bubble ? ACCENT : OFF) - 6;
		if (hardcore) {
			right = pill(ctx, font, right, top + 20, "HARDCORE", DANGER) - 6;
		}
		if (soulLink) {
			pill(ctx, font, right, top + 20, "❤ LINKED", SOUL);
		}

		beginClip(ctx);
		if (tab == 1) {
			renderPregame(ctx, mouseX, mouseY);
			endClip(ctx);
			return;
		}
		if (tab == 2) {
			renderAdmin(ctx, mouseX, mouseY);
			endClip(ctx);
			return;
		}

		// Setting cards
		card(ctx, mouseX, mouseY, col(0), row(0), "Bubble", "Keep everyone within the radius of the group");
		card(ctx, mouseX, mouseY, col(1), row(0), "Soul link", "Everyone shares one health bar. Hurt one, hurt all");
		card(ctx, mouseX, mouseY, col(0), row(1), "Hardcore reset", "One death ends the run and starts a new world");
		card(ctx, mouseX, mouseY, col(1), row(1), "Edge", wrap ? "Out one side, back in the other"
				: push ? "Nudged back toward the group" : "Teleported to the nearest player");
		cardAt(ctx, mouseX, mouseY, left + 14, row(2), W - 28, 70);
		ctx.drawString(font, Component.literal("Bubble radius"), left + 28, row(2) + 11, TEXT, false);
		String value = radius + (radius == 1 ? " block" : " blocks") + (vertical ? " in every direction" : " across");
		ctx.drawString(font, Component.literal(value), left + 28 + font.width("Bubble radius") + 8, row(2) + 11, ACCENT, false);

		// Your own setting: how close to the edge the wall starts to show
		cardAt(ctx, mouseX, mouseY, left + 14, wallY(), W - 28, 64);
		ctx.drawString(font, Component.literal("Replay when someone goes down"), left + 28, wallY() + 47, TEXT, false);
		ctx.drawString(font, Component.literal("(click to skip)"),
				left + 28 + font.width("Replay when someone goes down") + 6, wallY() + 47, MUTED, false);
		ctx.drawString(font, Component.literal("Wall shows within"), left + 28, wallY() + 9, TEXT, false);
		String wall = ClientSettings.wallAlways() ? "always visible" : ClientSettings.wallDistance + " blocks of the edge";
		ctx.drawString(font, Component.literal(wall), left + 28 + font.width("Wall shows within") + 8, wallY() + 9, ACCENT, false);
		String mine = "Just for you";
		ctx.drawString(font, Component.literal(mine), left + W - 28 - font.width(mine), wallY() + 9, MUTED, false);

		// Revive (shared rule): go down instead of dying, a teammate next to you gets you up
		cardAt(ctx, mouseX, mouseY, left + 14, reviveY(), W - 28, 44);
		ctx.drawString(font, Component.literal("Revive"), left + 28, reviveY() + 8, TEXT, false);
		String rdesc = ReviveClient.settings == null ? "" : ReviveClient.settings.enabled()
				? "Go down instead of dying; stand next to someone to get them up" : "";
		ctx.drawString(font, Component.literal(font.plainSubstrByWidth(rdesc, W - 28 - 28 - font.width("Revive") - 8 - 56)),
				left + 28 + font.width("Revive") + 8, reviveY() + 8, MUTED, false);
		ctx.drawString(font, Component.literal(font.plainSubstrByWidth(ReviveClient.summary(), W - 56)), left + 28, reviveY() + 26,
				ReviveClient.settings != null && ReviveClient.settings.enabled() ? ACCENT : MUTED, false);

		endClip(ctx);
		// Footer
		String note = ClientState.canEdit ? "Changes apply to everyone right away." : "Only operators can change these settings.";
		ctx.drawString(font, Component.literal(note), left + 22, top + H - 24, MUTED, false);
	}

	/** Clips drawing to the scrolling middle (the scissor box is in real screen space, so undo the panel's shrink). */
	private void beginClip(GuiGraphics ctx) {
		ctx.enableScissor(0, Math.round(viewTop() * uiScale), Math.round(width * uiScale), Math.round(viewBottom() * uiScale));
	}

	private void endClip(GuiGraphics ctx) {
		ctx.disableScissor();
		if (tab == 1) {
			String note = canEditPregame() ? "World settings apply when the next world is made. Creepers change now."
					: "Only operators can change these settings.";
			ctx.drawString(font, Component.literal(note), left + 22, top + H - 24, MUTED, false);
		} else if (tab == 2) {
			ctx.drawString(font, Component.literal("Red buttons need a second click. Hover a button to see what it does."),
					left + 22, top + H - 24, MUTED, false);
		}
		int max = maxScroll();
		if (max > 0) {
			// a scroll bar on the right edge of the middle, and fades so you can tell there's more
			int vt = viewTop();
			int vh = viewBottom() - vt;
			int barH = Math.max(18, vh * vh / (vh + max));
			int barY = vt + Math.round((vh - barH) * (contentScroll / (float) max));
			roundRect(ctx, left + W - 9, vt, 3, vh, 1, 0x22FFFFFF);
			roundRect(ctx, left + W - 9, barY, 3, barH, 1, 0x88FFFFFF);
			if (contentScroll > 0) {
				ctx.fillGradient(left + 6, vt, left + W - 12, vt + 10, 0xF2121418, 0x00121418);
			}
			if (contentScroll < max) {
				ctx.fillGradient(left + 6, viewBottom() - 10, left + W - 12, viewBottom(), 0x00121418, 0xF2121418);
			}
		}
	}

	/** Hover notes for the "Next run" rows: what each one actually does. */
	private static final String[] PRE_TIPS = {
			"How many Lucky Blocks are scattered through the next world when it's made.",
			"How many rainbow creepers spawn (they chase you and explode in colours). Changes straight away.",
			"How fast rainbow creepers move. Changes straight away.",
			"How many nuke creepers spawn: a much bigger blast than a normal creeper. Changes straight away.",
			"How many gatling skeletons spawn: they fire arrows very fast. Changes straight away.",
			"How many Watchers spawn. Don't look at them. Changes straight away.",
			"How many Jockey Jockeys spawn: a stack of mobs that hit you with every debuff. Changes straight away.",
			"How many swarm zombies spawn: a hundred tiny zombies at once. Changes straight away.",
			"Whether the start-of-run wheel spins when the next world starts.",
			"Whether the next world starts with the ready-up lobby (everyone presses Ready, nobody can get hurt).",
			"How hard the next world is.",
			"Type a seed for the next world, or leave it empty for a random one.",
			"What you have to do to win the next run (objectives and stages)."};

	@Override
	protected void renderScaled(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
		super.renderScaled(ctx, mouseX, mouseY, delta);
		if (tab != 1 || mouseY < viewTop() || mouseY >= viewBottom() || mouseX < left + 14 || mouseX >= left + W - 28 - 124 - 34) {
			return;
		}
		String tip = null;
		for (int i = 0; i < PRE_TIPS.length; i++) {
			if (mouseY >= preY(i) && mouseY < preY(i) + PRE_ROW - 4) {
				tip = PRE_TIPS[i];
			}
		}
		if (mouseY >= structTop() && mouseY < structTop() + pGroups.size() * STRUCT_ROW) {
			tip = "How often this mod's structures (villages, dungeons, ruins...) appear in the next world. None turns them off.";
		}
		if (tip != null) {
			ctx.renderTooltip(font, font.split(Component.literal(tip), 220), mouseX, mouseY);
		}
	}

	// ------------------------------------------------------------------ "Admin" tab (ops)

	/** Run buttons: label, what it does (tooltip), action, needs a second click. */
	private static final Object[][] RUN = {
			{"Start", "Ends the ready-up lobby and starts the run now", moonahmc.bubble.BubbleServer.ADMIN_START, false},
			{"Skip", "Skips the kill cam, or ends the vote now", moonahmc.bubble.BubbleServer.ADMIN_SKIP, false},
			{"Revive", "Brings back everyone spectating (Another life after a death)", moonahmc.bubble.BubbleServer.ADMIN_REVIVE, false},
			{"Spin", "Spins the start-of-run wheel now", moonahmc.bubble.BubbleServer.ADMIN_WHEEL, false},
			{"End run", "Goes to the post-game vote, like a death", moonahmc.bubble.BubbleServer.ADMIN_END_RUN, true},
			{"New world", "Throws this world away and makes a new one", moonahmc.bubble.BubbleServer.ADMIN_NEXT_WORLD, true},
			{"Retry seed", "A fresh copy of this world, same seed", moonahmc.bubble.BubbleServer.ADMIN_RETRY_SEED, true}};
	/** Pages: title, one line, which. */
	private static final String[][] TUNING = {
			{"Shop", "Windows, timings, open tabs", "shop"},
			{"Ragdolls", "How fragile everyone is", "ragdolls"},
			{"Events & director", "What happens, how often", "events"},
			{"Wheels", "Slices, weights, spin", "wheel"},
			{"Objectives", "Goals and stages", "goals"},
			{"Rules", "Revive, cooldown, falls", "rules"}};
	private final long[] armed = new long[RUN.length];
	private int statusAge;

	private int adminTop() {
		return top + 62 - contentScroll;
	}

	/** The Admin tab's height: status card, Run, Tuning (3 rows of tiles) and Players. */
	private static int adminHeight() {
		return 62 + 62 + 16 + 48 + 16 + 3 * 44 + 16 + 40 + 36;
	}

	private static boolean shopClosed() {
		return ClientState.catalog != null && ClientState.catalog.shopClosed();
	}

	/** A new shop catalogue arrived (the shop was opened/closed). */
	void refreshAdmin() {
		if (tab == 2) {
			rebuildWidgets();
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (tab == 2 && ClientState.canEdit && statusAge-- <= 0) {
			statusAge = 20;
			ClientPlayNetworking.send(new moonahmc.bubble.AdminStatus.Request());
		}
	}

	private void openPage(String which) {
		switch (which) {
			case "shop" -> minecraft.setScreen(new ShopWindowsScreen(this));
			case "ragdolls" -> minecraft.setScreen(new RagdollAdminScreen(this));
			case "events" -> minecraft.setScreen(new WheelAdminScreen(this, 2));
			case "wheel" -> minecraft.setScreen(new WheelAdminScreen(this, 0));
			case "goals" -> minecraft.setScreen(new GoalsEditorScreen(this));
			case "rules" -> minecraft.setScreen(new RulesScreen(this));
			case "currencies" -> minecraft.setScreen(new CurrencyAdminScreen(this));
			default -> {
			}
		}
	}

	private void initAdmin() {
		if (ClientState.catalog == null) {
			ClientPlayNetworking.send(new moonahmc.bubble.ShopPayloads.Request());
		}
		statusAge = 0;
		int y0 = adminTop();
		int inner = W - 28;
		// Run: four everyday buttons, then the three that end things (red, click twice)
		int rowA = y0 + 62 + 16;
		int rowB = rowA + 24;
		for (int i = 0; i < RUN.length; i++) {
			int index = i;
			boolean danger = (Boolean) RUN[i][3];
			int perRow = danger ? 3 : 4;
			int slot = danger ? i - 4 : i;
			int bw = (inner - (perRow - 1) * 6) / perRow;
			FlatButton b = new FlatButton(left + 14 + slot * (bw + 6), danger ? rowB : rowA, bw, 20, Component.literal((String) RUN[i][0]), null);
			b.color = danger ? DANGER : ACCENT;
			b.ghost = !danger;
			b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal((String) RUN[i][1])));
			b.setAction(() -> {
				long now = net.minecraft.Util.getMillis();
				if ((Boolean) RUN[index][3] && now - armed[index] > 3000) {
					armed[index] = now;
					b.setMessage(Component.literal("Sure?"));
					return;
				}
				armed[index] = 0;
				b.setMessage(Component.literal((String) RUN[index][0]));
				ClientPlayNetworking.send(new moonahmc.bubble.Lobby.Admin((Integer) RUN[index][2]));
			});
			if ((Integer) RUN[i][2] == moonahmc.bubble.BubbleServer.ADMIN_START) {
				b.active = ClientState.lobby != null && ClientState.lobby.active();
			}
			addRenderableWidget(b);
		}
		// Tuning: a tile per page
		int tilesTop = rowB + 24 + 16;
		int tw = (inner - 8) / 2;
		for (int i = 0; i < TUNING.length; i++) {
			String which = TUNING[i][2];
			addRenderableWidget(new Tile(left + 14 + (i % 2) * (tw + 8), tilesTop + (i / 2) * 44, tw, 40, TUNING[i][0], TUNING[i][1],
					() -> openPage(which)));
		}
		// Players
		int playersTop = tilesTop + 3 * 44 + 16;
		addRenderableWidget(new Tile(left + 14, playersTop, tw, 40, "Currencies", "Strange Matter and Eidolons", () -> openPage("currencies")));
	}

	private void renderAdmin(GuiGraphics ctx, int mouseX, int mouseY) {
		int y0 = adminTop();
		// status card
		roundRect(ctx, left + 14, y0, W - 28, 58, 6, CARD);
		java.util.List<String> lines = ClientState.adminStatus;
		if (lines.isEmpty()) {
			ctx.drawString(font, Component.literal("Loading the status..."), left + 24, y0 + 8, MUTED, false);
		}
		for (int i = 0; i < lines.size() && i < 4; i++) {
			ctx.drawString(font, Component.literal(font.plainSubstrByWidth(lines.get(i), W - 48)), left + 24, y0 + 7 + i * 12,
					i == 0 ? TEXT : MUTED, false);
		}
		int rowA = y0 + 62 + 16;
		ctx.drawString(font, Component.literal("RUN"), left + 16, rowA - 12, MUTED, false);
		int tilesTop = rowA + 24 + 24 + 16;
		ctx.drawString(font, Component.literal("TUNING"), left + 16, tilesTop - 12, MUTED, false);
		ctx.drawString(font, Component.literal("PLAYERS"), left + 16, tilesTop + 3 * 44 + 4, MUTED, false);
	}

	/** A card you click to open one of the admin pages. */
	static final class Tile extends AbstractWidget {
		private final String title;
		private final String line;
		private final Runnable action;

		Tile(int x, int y, int w, int h, String title, String line, Runnable action) {
			super(x, y, w, h, Component.literal(title));
			this.title = title;
			this.line = line;
			this.action = action;
		}

		@Override
		protected void renderWidget(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
			var tr = MinecraftClientHolder.text();
			roundRect(ctx, getX(), getY(), width, height, 6, isHovered() ? CARD_HOVER : CARD);
			roundRect(ctx, getX(), getY() + 8, 2, height - 16, 1, isHovered() ? ACCENT : 0xFF3A414B);
			ctx.drawString(tr, Component.literal(title), getX() + 10, getY() + 8, TEXT, false);
			ctx.drawString(tr, Component.literal(tr.plainSubstrByWidth(line, width - 30)), getX() + 10, getY() + 22, MUTED, false);
			ctx.drawString(tr, Component.literal("\u203A"), getX() + width - 12, getY() + 15, isHovered() ? ACCENT : MUTED, false);
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

	// ------------------------------------------------------------------ "Next run" tab

	private void switchTab(int t) {
		if (tab != t) {
			tab = t;
			contentScroll = 0;
			loadPregame();
			rebuildWidgets();
		}
	}

	private void loadPregame() {
		PregamePayloads.State st = ClientState.pregame;
		if (st == null) {
			return;
		}
		PregamePayloads.Settings s = st.settings();
		pLucky = s.lucky();
		pRainbow = s.rainbow();
		pNuke = s.nuke();
		pSpeed = s.speed();
		pDifficulty = s.difficulty();
		pGatling = s.gatling();
		pWheel = s.wheel();
		pLobby = s.lobby();
		pWatchers = s.watchers();
		pJockeys = s.jockeys();
		pSwarm = s.swarm();
		pMode = s.mode();
		pModes = s.modes();
		pSeed = s.nextSeed();
		pGroups = new ArrayList<>(s.groups());
	}

	/** New settings from the server (another op changed them, or ours came back). */
	void pregameChanged() {
		if (tab != 1) {
			loadPregame();
			return;
		}
		boolean typing = seedBox != null && seedBox.isFocused();
		String keep = pSeed;
		loadPregame();
		if (typing) {
			pSeed = keep;
		} else {
			rebuildWidgets();
		}
	}

	private boolean canEditPregame() {
		return ClientState.pregame != null && ClientState.pregame.canEdit();
	}

	private void sendPregame() {
		if (canEditPregame()) {
			ClientPlayNetworking.send(new PregamePayloads.Update(new PregamePayloads.Settings(pLucky, pRainbow, pNuke, pSpeed, pDifficulty, pSeed, pGroups, pGatling, pWheel, pLobby, pWatchers, pMode, pModes, pJockeys, pSwarm)));
		}
	}

	/**
	 * The Next run rows in the order they're shown: what applies now (the mobs) under one heading, what waits for the
	 * next world under another. Numbers are the rows' own indices (the order the widgets were always made in).
	 */
	private static final int[] PRE_ORDER = {1, 2, 3, 4, 5, 6, 7, 0, 12, 10, 11, 8, 9};
	/** How many rows come before the "Next world" heading. */
	private static final int PRE_NOW = 7;
	private static final int PRE_HEAD = 16;

	private int preY(int i) {
		int pos = 0;
		while (pos < PRE_ORDER.length && PRE_ORDER[pos] != i) {
			pos++;
		}
		return top + 62 + PRE_HEAD + (pos >= PRE_NOW ? PRE_HEAD : 0) + pos * PRE_ROW - contentScroll;
	}

	private int structTop() {
		return top + 62 + 2 * PRE_HEAD + PRE_ORDER.length * PRE_ROW - contentScroll + 16;
	}

	/** Every structure group is listed now (the whole panel scrolls). */
	private int structRows() {
		return Math.max(1, pGroups.size());
	}

	private static String[] speedLabels() {
		String[] out = new String[Pregame.SPEEDS.length];
		for (int i = 0; i < out.length; i++) {
			out[i] = Pregame.SPEEDS[i] + "x";
		}
		return out;
	}

	private static int speedIndex(int speed) {
		int best = 0;
		for (int i = 0; i < Pregame.SPEEDS.length; i++) {
			if (Math.abs(Pregame.SPEEDS[i] - speed) < Math.abs(Pregame.SPEEDS[best] - speed)) {
				best = i;
			}
		}
		return best;
	}

	private void initPregame() {
		boolean edit = canEditPregame();
		int cx = left + W - 28 - 124;
		addPregame(new Choice(cx - 30, preY(12) + 3, 154, 16, pModes.isEmpty() ? new String[]{"-"} : pModes.toArray(new String[0]),
				() -> pMode, v -> pMode = v), edit);
		addPregame(new Choice(cx, preY(0) + 3, 124, 16, Pregame.AMOUNTS, () -> pLucky, v -> pLucky = v), edit);
		addPregame(new Choice(cx, preY(1) + 3, 124, 16, Pregame.MOB_AMOUNTS, () -> pRainbow, v -> pRainbow = v), edit);
		addPregame(new Choice(cx, preY(2) + 3, 124, 16, speedLabels(), () -> speedIndex(pSpeed), v -> pSpeed = Pregame.SPEEDS[v]), edit);
		addPregame(new Choice(cx, preY(3) + 3, 124, 16, Pregame.MOB_AMOUNTS, () -> pNuke, v -> pNuke = v), edit);
		addPregame(new Choice(cx, preY(4) + 3, 124, 16, Pregame.MOB_AMOUNTS, () -> pGatling, v -> pGatling = v), edit);
		addPregame(new Choice(cx, preY(5) + 3, 124, 16, Pregame.MOB_AMOUNTS, () -> pWatchers, v -> pWatchers = v), edit);
		addPregame(new Choice(cx, preY(6) + 3, 124, 16, Pregame.MOB_AMOUNTS, () -> pJockeys, v -> pJockeys = v), edit);
		addPregame(new Choice(cx, preY(7) + 3, 124, 16, Pregame.MOB_AMOUNTS, () -> pSwarm, v -> pSwarm = v), edit);
		addPregame(new Choice(cx, preY(8) + 3, 124, 16, Pregame.ON_OFF, () -> pWheel, v -> pWheel = v), edit);
		addPregame(new Choice(cx, preY(9) + 3, 124, 16, Pregame.ON_OFF, () -> pLobby, v -> pLobby = v), edit);
		addPregame(new Choice(cx, preY(10) + 3, 124, 16, Pregame.DIFFICULTIES, () -> pDifficulty, v -> pDifficulty = v), edit);
		seedBox = new EditBox(font, cx, preY(11) + 3, 124, 16, Component.literal("Seed"));
		seedBox.setMaxLength(32);
		seedBox.setValue(pSeed);
		seedBox.setHint(Component.literal("random").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
		seedBox.setEditable(edit);
		seedBox.setResponder(v -> {
			if (!v.equals(pSeed)) {
				pSeed = v;
				sendPregame();
			}
		});
		addRenderableWidget(seedBox);
		int rows = structRows();
		structScroll = Math.max(0, Math.min(structScroll, Math.max(0, pGroups.size() - rows)));
		for (int i = 0; i < rows && structScroll + i < pGroups.size(); i++) {
			int index = structScroll + i;
			addPregame(new Choice(cx, structTop() + i * STRUCT_ROW + 1, 124, 16, Pregame.AMOUNTS, () -> pGroups.get(index).level(), v -> {
				PregamePayloads.Group g = pGroups.get(index);
				pGroups.set(index, new PregamePayloads.Group(g.namespace(), g.name(), g.count(), v));
			}), edit);
		}
	}

	private void addPregame(Choice c, boolean edit) {
		c.active = edit;
		c.onChange = this::sendPregame;
		addRenderableWidget(c);
	}

	@Override
	protected boolean scrolled(double x, double y, double h, double v) {
		if (maxScroll() > 0) {
			int next = Math.max(0, Math.min(maxScroll(), contentScroll - (int) Math.signum(v) * 26));
			if (next != contentScroll) {
				boolean typing = seedBox != null && seedBox.isFocused();
				contentScroll = next;
				rebuildWidgets();
				if (typing && seedBox != null && seedBox.visible) {
					setFocused(seedBox);
				}
			}
			return true;
		}
		return super.scrolled(x, y, h, v);
	}

	private void renderPregame(GuiGraphics ctx, int mouseX, int mouseY) {
		String[] rows = {"Lucky blocks in the world", "Rainbow creepers", "Rainbow creeper speed", "Nuke creepers", "Gatling skeletons",
				"Watchers (don't look!)", "Jockey Jockeys (every debuff)", "Swarm zombies (100 tiny ones)", "Start-of-run wheel",
				"Ready-up lobby", "Difficulty", "Seed", "Game mode"};
		ctx.drawString(font, Component.literal("APPLIES NOW"), left + 16, preY(PRE_ORDER[0]) - 12, MUTED, false);
		String later = "NEXT WORLD";
		ctx.drawString(font, Component.literal(later), left + 16, preY(PRE_ORDER[PRE_NOW]) - 12, MUTED, false);
		ctx.drawString(font, Component.literal("when the next world is made"), left + 16 + font.width(later) + 6, preY(PRE_ORDER[PRE_NOW]) - 12,
				0xFF5A606B, false);
		for (int i = 0; i < rows.length; i++) {
			cardAt(ctx, mouseX, mouseY, left + 14, preY(i), W - 28, PRE_ROW - 4);
			ctx.drawString(font, Component.literal(rows[i]), left + 24, preY(i) + 7, TEXT, false);
		}
		ctx.drawString(font, Component.literal("STRUCTURES"), left + 16, structTop() - 12, MUTED, false);
		ctx.drawString(font, Component.literal("next world, per mod"), left + 16 + font.width("STRUCTURES") + 6, structTop() - 12, 0xFF5A606B, false);
		int rowsShown = structRows();
		for (int i = 0; i < rowsShown && structScroll + i < pGroups.size(); i++) {
			PregamePayloads.Group g = pGroups.get(structScroll + i);
			int y = structTop() + i * STRUCT_ROW;
			roundRect(ctx, left + 14, y, W - 28, STRUCT_ROW - 2, 5, (i + structScroll) % 2 == 0 ? CARD : 0xFF171A20);
			String label = font.plainSubstrByWidth(g.name(), 170);
			ctx.drawString(font, Component.literal(label), left + 24, y + 5, TEXT, false);
			ctx.drawString(font, Component.literal(g.count() + (g.count() == 1 ? " kind" : " kinds")), left + 24 + font.width(label) + 6, y + 5, MUTED, false);
		}
	}

	/** A value you step through: click the left half for the previous one, the right half for the next. */
	static final class Choice extends AbstractWidget {
		private final String[] options;
		private final java.util.function.IntSupplier getter;
		private final java.util.function.IntConsumer setter;
		Runnable onChange = () -> {
		};

		Choice(int x, int y, int w, int h, String[] options, java.util.function.IntSupplier getter, java.util.function.IntConsumer setter) {
			super(x, y, w, h, Component.empty());
			this.options = options;
			this.getter = getter;
			this.setter = setter;
		}

		@Override
		protected void renderWidget(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
			var tr = MinecraftClientHolder.text();
			int v = Math.max(0, Math.min(options.length - 1, getter.getAsInt()));
			roundRect(ctx, getX(), getY(), width, height, 6, active ? (isHovered() ? 0xFF323843 : 0xFF2A2F37) : 0xFF23272E);
			boolean onLeft = mouseX < getX() + width / 2;
			int arrowOn = active ? TEXT : 0xFF4A5059;
			int arrowOff = active ? MUTED : 0xFF4A5059;
			ctx.drawString(tr, Component.literal("<"), getX() + 7, getY() + (height - 8) / 2, isHovered() && onLeft ? arrowOn : arrowOff, false);
			ctx.drawString(tr, Component.literal(">"), getX() + width - 7 - tr.width(">"), getY() + (height - 8) / 2, isHovered() && !onLeft ? arrowOn : arrowOff, false);
			String label = options[v];
			int color = !active ? MUTED : label.equals("Off") ? DANGER : ACCENT;
			ctx.drawString(tr, Component.literal(label), getX() + (width - tr.width(label)) / 2, getY() + (height - 8) / 2, color, false);
		}

		@Override
		public void onClick(double mouseX, double mouseY) {
			int v = getter.getAsInt();
			int next = mouseX < getX() + width / 2.0 ? Math.max(0, v - 1) : Math.min(options.length - 1, v + 1);
			if (next != v) {
				setter.accept(next);
				onChange.run();
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput builder) {
			builder.add(NarratedElementType.TITLE, Component.literal(options[Math.max(0, Math.min(options.length - 1, getter.getAsInt()))]));
		}
	}

	private void card(GuiGraphics ctx, int mouseX, int mouseY, int x, int y, String title, String desc) {
		cardAt(ctx, mouseX, mouseY, x, y, cardW, CARD_H);
		ctx.drawString(font, Component.literal(title), x + 12, y + 12, TEXT, false);
		List<FormattedCharSequence> lines = font.split(Component.literal(desc), cardW - 24);
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			ctx.drawString(font, lines.get(i), x + 12, y + 30 + i * 10, MUTED, false);
		}
	}

	private static void cardAt(GuiGraphics ctx, int mouseX, int mouseY, int x, int y, int w, int h) {
		boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
		roundRect(ctx, x, y, w, h, 6, hover ? CARD_HOVER : CARD);
	}

	// ------------------------------------------------------------------ shared drawing helpers

	/** The dark rounded panel with a soft shadow. */
	static void panel(GuiGraphics ctx, int x, int y, int w, int h) {
		roundRect(ctx, x - 3, y - 2, w + 6, h + 6, 10, 0x40000000);
		roundRect(ctx, x, y, w, h, 8, BG);
		ctx.fill(x + 8, y, x + w - 8, y + 1, 0x14FFFFFF);
	}

	static void bigText(GuiGraphics ctx, Font tr, String s, int x, int y) {
		ctx.pose().pushPose();
		ctx.pose().translate(x, y, 0);
		ctx.pose().scale(1.6f, 1.6f, 1f);
		ctx.drawString(tr, Component.literal(s), 0, 0, TEXT, false);
		ctx.pose().popPose();
	}

	/** Draws a pill ending at `right`; returns its left edge. */
	static int pill(GuiGraphics ctx, Font tr, int right, int y, String label, int color) {
		int w = tr.width(label) + 12;
		int bg = (color & 0x00FFFFFF) | 0x33000000;
		roundRect(ctx, right - w, y, w, 14, 7, bg);
		ctx.drawString(tr, Component.literal(label), right - w + 6, y + 3, color, false);
		return right - w;
	}

	/** Filled rectangle with rounded corners. */
	static void roundRect(GuiGraphics ctx, int x, int y, int w, int h, int r, int color) {
		r = Math.min(r, Math.min(w, h) / 2);
		if (w <= 0 || h <= 0) {
			return;
		}
		if (r <= 0) {
			ctx.fill(x, y, x + w, y + h, color);
			return;
		}
		ctx.fill(x, y + r, x + w, y + h - r, color);
		for (int i = 0; i < r; i++) {
			double dy = r - i - 0.5;
			int inset = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
			ctx.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
			ctx.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
		}
	}

	static final net.minecraft.resources.ResourceLocation MATTER_ICON = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("bubble", "textures/gui/strange_matter.png");

	/** The Strange Matter coin, drawn `size` pixels wide. */
	static void matterIcon(GuiGraphics ctx, int x, int y, int size) {
		com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
		ctx.blit(MATTER_ICON, x, y, size, size, 0, 0, 64, 64, 64, 64);
		com.mojang.blaze3d.systems.RenderSystem.disableBlend();
	}

	/** "[coin] 1,234" ending at `right`; returns the left edge. */
	static int matterAmount(GuiGraphics ctx, Font tr, int right, int y, String amount, int color) {
		int w = tr.width(amount);
		ctx.drawString(tr, Component.literal(amount), right - w, y, color, false);
		matterIcon(ctx, right - w - 12, y - 1, 10);
		return right - w - 12;
	}

	static int lerpColor(int a, int b, float t) {
		int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, aa = (a >>> 24);
		int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255, ba = (b >>> 24);
		return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
				| ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
	}

	// ------------------------------------------------------------------ widgets

	/** Animated on/off switch. */
	static final class Toggle extends AbstractWidget {
		private final BooleanSupplier getter;
		private final Consumer<Boolean> setter;
		private final int onColor;
		private float anim;

		Toggle(int x, int y, BooleanSupplier getter, Consumer<Boolean> setter) {
			this(x, y, getter, setter, ACCENT);
		}

		Toggle(int x, int y, BooleanSupplier getter, Consumer<Boolean> setter, int onColor) {
			super(x, y, 34, 18, Component.empty());
			this.getter = getter;
			this.setter = setter;
			this.onColor = onColor;
			this.anim = getter.getAsBoolean() ? 1f : 0f;
		}

		@Override
		protected void renderWidget(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
			float target = getter.getAsBoolean() ? 1f : 0f;
			anim += (target - anim) * 0.3f;
			int track = active ? lerpColor(OFF, onColor, anim) : 0xFF2A2E35;
			if (active && isHovered()) {
				roundRect(ctx, getX() - 1, getY() - 1, width + 2, height + 2, 10, 0x30FFFFFF);
			}
			roundRect(ctx, getX(), getY(), width, height, 9, track);
			int knob = getX() + 3 + Math.round(anim * (width - 18));
			roundRect(ctx, knob, getY() + 3, 12, 12, 6, active ? 0xFFFFFFFF : 0xFF7A828E);
		}

		@Override
		public void onClick(double mouseX, double mouseY) {
			setter.accept(!getter.getAsBoolean());
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput builder) {
			builder.add(NarratedElementType.TITLE, Component.literal(getter.getAsBoolean() ? "On" : "Off"));
		}
	}

	/** Two-option switch, e.g. Teleport | Push. getter true = the right option. */
	/** Three-way switch (the bubble edge: pull, push, wrap). */
	static final class Segmented3 extends AbstractWidget {
		private final String[] labels;
		private final java.util.function.IntSupplier getter;
		private final java.util.function.IntConsumer setter;
		private float anim;

		Segmented3(int x, int y, int w, int h, String[] labels, java.util.function.IntSupplier getter, java.util.function.IntConsumer setter) {
			super(x, y, w, h, Component.empty());
			this.labels = labels;
			this.getter = getter;
			this.setter = setter;
			this.anim = getter.getAsInt();
		}

		@Override
		protected void renderWidget(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
			int on = getter.getAsInt();
			anim += (on - anim) * 0.3f;
			var tr = MinecraftClientHolder.text();
			roundRect(ctx, getX(), getY(), width, height, 6, active ? 0xFF2A2F37 : 0xFF23272E);
			int part = width / labels.length;
			int kx = getX() + 2 + Math.round(anim * part);
			roundRect(ctx, kx, getY() + 2, part - 4 + (on == labels.length - 1 ? width - part * labels.length : 0), height - 4, 5, active ? ACCENT : 0xFF4A5059);
			for (int i = 0; i < labels.length; i++) {
				int px = getX() + i * part;
				boolean hover = active && isHovered() && mouseX >= px && mouseX < px + part;
				int color = i == on ? 0xFF0D1F17 : hover ? TEXT : MUTED;
				ctx.drawString(tr, Component.literal(labels[i]), px + (part - tr.width(labels[i])) / 2, getY() + (height - 8) / 2, color, false);
			}
		}

		@Override
		public void onClick(double mouseX, double mouseY) {
			int i = Math.max(0, Math.min(labels.length - 1, (int) ((mouseX - getX()) / (width / (double) labels.length))));
			if (i != getter.getAsInt()) {
				setter.accept(i);
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput builder) {
			builder.add(NarratedElementType.TITLE, Component.literal(labels[Math.max(0, Math.min(labels.length - 1, getter.getAsInt()))]));
		}
	}

	static final class Segmented extends AbstractWidget {
		private final String leftLabel;
		private final String rightLabel;
		private final BooleanSupplier getter;
		private final Consumer<Boolean> setter;
		private float anim;

		Segmented(int x, int y, int w, int h, String leftLabel, String rightLabel, BooleanSupplier getter, Consumer<Boolean> setter) {
			super(x, y, w, h, Component.empty());
			this.leftLabel = leftLabel;
			this.rightLabel = rightLabel;
			this.getter = getter;
			this.setter = setter;
			this.anim = getter.getAsBoolean() ? 1f : 0f;
		}

		@Override
		protected void renderWidget(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
			anim += ((getter.getAsBoolean() ? 1f : 0f) - anim) * 0.3f;
			var tr = MinecraftClientHolder.text();
			roundRect(ctx, getX(), getY(), width, height, 6, active ? 0xFF2A2F37 : 0xFF23272E);
			int half = width / 2;
			int kx = getX() + 2 + Math.round(anim * (half - 2));
			roundRect(ctx, kx, getY() + 2, half - 2, height - 4, 5, active ? ACCENT : 0xFF4A5059);
			boolean rightOn = getter.getAsBoolean();
			int leftColor = !rightOn ? 0xFF0D1F17 : (active && isHovered() && mouseX < getX() + half ? TEXT : MUTED);
			int rightColor = rightOn ? 0xFF0D1F17 : (active && isHovered() && mouseX >= getX() + half ? TEXT : MUTED);
			ctx.drawString(tr, Component.literal(leftLabel), getX() + (half - tr.width(leftLabel)) / 2, getY() + (height - 8) / 2, leftColor, false);
			ctx.drawString(tr, Component.literal(rightLabel), getX() + half + (half - tr.width(rightLabel)) / 2, getY() + (height - 8) / 2, rightColor, false);
		}

		@Override
		public void onClick(double mouseX, double mouseY) {
			boolean right = mouseX >= getX() + width / 2.0;
			if (right != getter.getAsBoolean()) {
				setter.accept(right);
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput builder) {
			builder.add(NarratedElementType.TITLE, Component.literal(getter.getAsBoolean() ? rightLabel : leftLabel));
		}
	}

	/**
	 * Radius slider, 2 to 500. The scale is stretched at the low end so small bubbles are easy to pick:
	 * steps of 1 up to 30, 5 up to 100, then 10. Sends the new value when released.
	 */
	final class RadiusSlider extends AbstractSliderButton {
		boolean dragging;

		RadiusSlider(int x, int y, int w, int h) {
			super(x, y, w, h, Component.empty(), toValue(radius));
		}

		private static double toValue(int r) {
			return Math.sqrt((r - MIN) / (double) (MAX - MIN));
		}

		private static int fromValue(double v) {
			double raw = MIN + v * v * (MAX - MIN);
			int step = raw < 30 ? 1 : raw < 100 ? 5 : 10;
			int r = (int) (Math.round(raw / step) * step);
			return Math.max(MIN, Math.min(MAX, r));
		}

		void setRadius(int r) {
			value = toValue(r);
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.empty());
		}

		@Override
		protected void applyValue() {
			radius = fromValue(value);
		}

		@Override
		public void onClick(double mouseX, double mouseY) {
			dragging = true;
			super.onClick(mouseX, mouseY);
		}

		@Override
		public void onRelease(double mouseX, double mouseY) {
			super.onRelease(mouseX, mouseY);
			if (dragging) {
				dragging = false;
				send();
			}
		}

		@Override
		public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
			boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
			if (handled) {
				send();
			}
			return handled;
		}

		@Override
		public void renderWidget(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
			var tr = MinecraftClientHolder.text();
			int cy = getY() + height / 2;
			roundRect(ctx, getX(), cy - 2, width, 4, 2, 0xFF2E333B);
			int fx = getX() + 6 + (int) (value * (width - 12));
			roundRect(ctx, getX(), cy - 2, fx - getX(), 4, 2, active ? ACCENT : 0xFF4A5059);
			int knob = active ? (isHovered() || dragging ? 0xFFFFFFFF : 0xFFE6E9EE) : 0xFF7A828E;
			roundRect(ctx, fx - 6, cy - 7, 12, 14, 5, knob);
			int labelY = getY() + height + 4;
			for (int mark : new int[]{MIN, 25, 100, 250, MAX}) {
				String s = Integer.toString(mark);
				int mx = getX() + 6 + (int) (toValue(mark) * (width - 12));
				int tx = mark == MIN ? getX() : mark == MAX ? getX() + width - tr.width(s) : mx - tr.width(s) / 2;
				ctx.drawString(tr, Component.literal(s), tx, labelY, MUTED, false);
			}
		}
	}

	/** Your wall distance: 4 to 64 blocks in steps of 2, then "always". Saved when you let go. */
	static final class WallSlider extends AbstractSliderButton {
		private static final int STEPS = (ClientSettings.MAX_WALL - ClientSettings.MIN_WALL) / 2 + 1; // 4..64, then always

		WallSlider(int x, int y, int w, int h) {
			super(x, y, w, h, Component.empty(), toValue(ClientSettings.wallDistance));
		}

		private static double toValue(int d) {
			int i = d >= ClientSettings.ALWAYS ? STEPS : (Math.max(ClientSettings.MIN_WALL, d) - ClientSettings.MIN_WALL) / 2;
			return i / (double) STEPS;
		}

		private static int fromValue(double v) {
			int i = (int) Math.round(v * STEPS);
			return i >= STEPS ? ClientSettings.ALWAYS : ClientSettings.MIN_WALL + 2 * i;
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.empty());
		}

		@Override
		protected void applyValue() {
			ClientSettings.wallDistance = fromValue(value);
		}

		@Override
		public void onRelease(double mouseX, double mouseY) {
			super.onRelease(mouseX, mouseY);
			ClientSettings.save();
		}

		@Override
		public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
			boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
			if (handled) {
				ClientSettings.save();
			}
			return handled;
		}

		@Override
		public void renderWidget(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
			int cy = getY() + height / 2;
			roundRect(ctx, getX(), cy - 2, width, 4, 2, 0xFF2E333B);
			int fx = getX() + 6 + (int) (value * (width - 12));
			roundRect(ctx, getX(), cy - 2, fx - getX(), 4, 2, ACCENT);
			roundRect(ctx, fx - 6, cy - 6, 12, 12, 5, isHovered() ? 0xFFFFFFFF : 0xFFE6E9EE);
		}
	}

	/** Accent-coloured button with rounded corners. ghost = dark outline style. */
	static class FlatButton extends AbstractWidget {
		private Runnable action;
		boolean ghost;
		/** Optional accent colour (0 = the default mint). */
		int color;
		/** Draw as "selected" (filled) even in ghost style. */
		boolean selected;

		FlatButton(int x, int y, int w, int h, Component label, Runnable action) {
			super(x, y, w, h, label);
			this.action = action;
		}

		@Override
		protected void renderWidget(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
			var tr = MinecraftClientHolder.text();
			int textColor;
			int base = color != 0 ? color : ACCENT;
			if (!active) {
				roundRect(ctx, getX(), getY(), width, height, 5, 0xFF23272E);
				textColor = 0xFF5A616C;
			} else if (ghost && !selected) {
				roundRect(ctx, getX(), getY(), width, height, 5, isHovered() ? 0xFF343A44 : 0xFF2A2F37);
				textColor = TEXT;
			} else {
				roundRect(ctx, getX(), getY(), width, height, 5, isHovered() ? lerpColor(base, 0xFFFFFFFF, 0.2f) : base);
				textColor = 0xFF0D1F17;
			}
			ctx.drawString(tr, getMessage(), getX() + (width - tr.width(getMessage())) / 2, getY() + (height - 8) / 2, textColor, false);
		}

		void setAction(Runnable action) {
			this.action = action;
		}

		@Override
		public void onClick(double mouseX, double mouseY) {
			if (action != null) {
				action.run();
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput builder) {
			defaultButtonNarrationText(builder);
		}
	}

	/** The small round button shown on the inventory screen. */
	public static final class IconButton extends AbstractWidget {
		private final Runnable action;

		public IconButton(int x, int y, Runnable action) {
			super(x, y, 20, 20, Component.literal("Bubble"));
			this.action = action;
			setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Bubble settings")));
		}

		@Override
		protected void renderWidget(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
			roundRect(ctx, getX(), getY(), width, height, 6, isHovered() ? CARD_HOVER : BG);
			int cx = getX() + width / 2;
			int cy = getY() + height / 2;
			// a ring drawn pixel by pixel
			for (int dx = -6; dx <= 6; dx++) {
				for (int dy = -6; dy <= 6; dy++) {
					double d = Math.sqrt(dx * dx + dy * dy);
					if (d >= 4.4 && d <= 6.2) {
						ctx.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, ACCENT);
					}
				}
			}
			ctx.fill(cx - 1, cy - 1, cx + 1, cy + 1, TEXT);
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
