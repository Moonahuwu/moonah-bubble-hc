package moonahmc.bubble.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import moonahmc.bubble.Goals;
import moonahmc.bubble.client.net.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

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
 * Ops: add and remove objectives (and stages) in any game mode, from the Bubble menu's Admin tab. Every change is
 * saved to config/bubble-goals.json on the server and applies to the run straight away.
 */
public final class GoalsEditorScreen extends ScaledScreen {
	private static final int W = 480;
	private static final int H = 340;
	private static final int LIST_W = 150;
	private static final int ROW_H = 28;
	private static final String[] TYPE_LABELS = {"Kill a mob", "Have an item", "Enter a dimension", "Advancement", "Find a structure", "Bubble event"};

	private static int modeIndex = -1;
	private static int stage;
	private static int type;
	private static int caseIndex;
	private static String id = "";
	private static String count = "1";
	private static String text = "";
	private static String reward = "20";
	private static String cases = "1";
	private static String stageName = "";

	private final Screen parent;
	private int left;
	private int top;
	private int scroll;
	private long armedStage;
	private long armedUse;
	private JsonObject modes = new JsonObject();
	private List<String> modeIds = new ArrayList<>();

	GoalsEditorScreen(Screen parent) {
		super(Component.literal("Edit objectives"));
		this.parent = parent;
		ClientPlayNetworking.send(new Goals.Edit(Goals.EDIT_REQUEST, "", 0, 0, ""));
	}

	@Override
	protected int panelWidth() {
		return W;
	}

	@Override
	protected int panelHeight() {
		return H;
	}

	/** New config from the server (after any op's edit). */
	void refresh() {
		rebuildWidgets();
	}

	private void parse() {
		Goals.Config c = ClientState.goalsConfig;
		modes = new JsonObject();
		modeIds = new ArrayList<>();
		if (c == null) {
			return;
		}
		try {
			modes = JsonParser.parseString(c.json()).getAsJsonObject();
		} catch (Exception e) {
			return;
		}
		modeIds = new ArrayList<>(modes.keySet());
		if (modeIndex < 0 || modeIndex >= modeIds.size()) {
			modeIndex = Math.max(0, modeIds.indexOf(c.runMode()));
		}
	}

	private String modeId() {
		return modeIds.isEmpty() ? "" : modeIds.get(Math.min(modeIndex, modeIds.size() - 1));
	}

	private JsonObject mode() {
		String m = modeId();
		return m.isEmpty() ? null : modes.getAsJsonObject(m);
	}

	private JsonArray stages() {
		JsonObject m = mode();
		return m == null || !m.has("stages") ? new JsonArray() : m.getAsJsonArray("stages");
	}

	/** Objectives in the selected stage (or the bonus list). */
	private JsonArray objectives() {
		JsonObject m = mode();
		if (m == null) {
			return new JsonArray();
		}
		if (stage == Goals.BONUS) {
			return m.has("subgoals") ? m.getAsJsonArray("subgoals") : new JsonArray();
		}
		JsonArray st = stages();
		if (stage < 0 || stage >= st.size()) {
			return new JsonArray();
		}
		JsonObject s = st.get(stage).getAsJsonObject();
		return s.has("objectives") ? s.getAsJsonArray("objectives") : new JsonArray();
	}

	private int visibleRows() {
		return (formY() - 8 - listTop()) / ROW_H;
	}

	private int listTop() {
		return top + 74;
	}

	private int formY() {
		return top + H - 112;
	}

	private int rightX() {
		return left + 14 + LIST_W + 10;
	}

	private int rightW() {
		return W - 14 - (rightX() - left);
	}

	private int stageRowY(int i) {
		return top + 56 + i * 20;
	}

	@Override
	protected void init() {
		fit();
		left = (width - W) / 2;
		top = (height - H) / 2;
		parse();
		Goals.Config cfg = ClientState.goalsConfig;
		addRenderableWidget(new BubbleScreen.FlatButton(left + W - 14 - 64, top + H - 30, 64, 20, Component.literal("Back"), this::onClose)).ghost = true;
		if (cfg == null || modeIds.isEmpty()) {
			return;
		}
		JsonArray st = stages();
		if (stage != Goals.BONUS && stage >= st.size()) {
			stage = Math.max(0, st.size() - 1);
		}
		// mode picker
		String[] names = modeIds.stream().map(m -> modes.getAsJsonObject(m).get("name").getAsString()).toArray(String[]::new);
		BubbleScreen.Choice modeChoice = addRenderableWidget(new BubbleScreen.Choice(left + W - 14 - 180, top + 14, 180, 16, names, () -> modeIndex, v -> {
			modeIndex = v;
			stage = 0;
			scroll = 0;
		}));
		modeChoice.onChange = this::rebuildWidgets;
		if (!modeId().equals(cfg.runMode())) {
			BubbleScreen.FlatButton use = addRenderableWidget(new BubbleScreen.FlatButton(left + W - 14 - 180, top + 34, 180, 16,
					Component.literal("Use this mode for this run"), null));
			use.color = DANGER;
			use.setAction(() -> {
				long now = Util.getMillis();
				if (now - armedUse > 3000) {
					armedUse = now;
					use.setMessage(Component.literal("Click again: restarts the goals"));
					return;
				}
				ClientPlayNetworking.send(new Goals.Edit(Goals.EDIT_USE_NOW, modeId(), 0, 0, ""));
			});
			use.setTooltip(Tooltip.create(Component.literal("This run's goals switch to this mode and start over at stage 1. Kills and places found so far still count.")));
		}

		// stages column: add / delete
		int by = top + H - 70;
		EditBox nameBox = addRenderableWidget(new EditBox(font, left + 14, by, LIST_W, 16, Component.literal("Stage name")));
		nameBox.setMaxLength(48);
		nameBox.setValue(stageName);
		nameBox.setHint(Component.literal("New stage name").withStyle(ChatFormatting.DARK_GRAY));
		nameBox.setResponder(v -> stageName = v);
		addRenderableWidget(new BubbleScreen.FlatButton(left + 14, by + 20, 60, 16, Component.literal("+ Stage"), () -> {
			if (!stageName.isBlank()) {
				ClientPlayNetworking.send(new Goals.Edit(Goals.EDIT_ADD_STAGE, modeId(), 0, 0, stageName));
				stage = stages().size();
				stageName = "";
			}
		}));
		if (stage != Goals.BONUS) {
			BubbleScreen.FlatButton del = addRenderableWidget(new BubbleScreen.FlatButton(left + 14 + 64, by + 20, LIST_W - 64, 16,
					Component.literal("Delete stage"), null));
			del.color = DANGER;
			del.active = st.size() > 1;
			del.setAction(() -> {
				long now = Util.getMillis();
				if (now - armedStage > 3000) {
					armedStage = now;
					del.setMessage(Component.literal("Click to confirm"));
					return;
				}
				ClientPlayNetworking.send(new Goals.Edit(Goals.EDIT_REMOVE_STAGE, modeId(), stage, 0, ""));
				stage = Math.max(0, stage - 1);
			});
		}

		// remove buttons on the visible objectives
		JsonArray obj = objectives();
		scroll = Math.max(0, Math.min(scroll, Math.max(0, obj.size() - visibleRows())));
		for (int i = scroll; i < obj.size() && i < scroll + visibleRows(); i++) {
			int index = i;
			int ry = listTop() + (i - scroll) * ROW_H;
			BubbleScreen.FlatButton x = addRenderableWidget(new BubbleScreen.FlatButton(rightX() + rightW() - 24, ry + 5, 18, 16, Component.literal("✕"),
					() -> ClientPlayNetworking.send(new Goals.Edit(Goals.EDIT_REMOVE_OBJECTIVE, modeId(), stage, index, ""))));
			x.color = DANGER;
			x.ghost = true;
			x.setTooltip(Tooltip.create(Component.literal("Remove this objective")));
		}

		// add form
		int fx = rightX();
		int fy = formY() + 14;
		addRenderableWidget(new BubbleScreen.Choice(fx, fy, 118, 16, TYPE_LABELS, () -> type, v -> type = v));
		EditBox idBox = addRenderableWidget(new EditBox(font, fx + 122, fy, rightW() - 122 - 56, 16, Component.literal("ID")));
		idBox.setMaxLength(128);
		idBox.setValue(id);
		idBox.setHint(Component.literal("ID, e.g. minecraft:diamond").withStyle(ChatFormatting.DARK_GRAY));
		idBox.setResponder(v -> id = v);
		BubbleScreen.FlatButton fill = addRenderableWidget(new BubbleScreen.FlatButton(fx + rightW() - 52, fy, 52, 16, Component.literal("Fill"), () -> {
			String f = fillId();
			if (f != null) {
				id = f;
				idBox.setValue(f);
			}
		}));
		fill.ghost = true;
		fill.setTooltip(Tooltip.create(Component.literal("Item: what you're holding. Mob: what you're looking at. Dimension: where you are. Event: cycles the events.")));

		EditBox countBox = addRenderableWidget(new EditBox(font, fx, fy + 20, 40, 16, Component.literal("Count")));
		countBox.setFilter(t -> t.matches("[0-9]{0,6}"));
		countBox.setValue(count);
		countBox.setHint(Component.literal("count").withStyle(ChatFormatting.DARK_GRAY));
		countBox.setResponder(v -> count = v);
		countBox.setTooltip(Tooltip.create(Component.literal("How many (kills, items, events)")));
		EditBox textBox = addRenderableWidget(new EditBox(font, fx + 44, fy + 20, rightW() - 44 - 60, 16, Component.literal("Text")));
		textBox.setMaxLength(60);
		textBox.setValue(text);
		textBox.setHint(Component.literal("Text on the HUD (blank = automatic)").withStyle(ChatFormatting.DARK_GRAY));
		textBox.setResponder(v -> text = v);
		EditBox rewardBox = addRenderableWidget(new EditBox(font, fx + rightW() - 56, fy + 20, 56, 16, Component.literal("Reward")));
		rewardBox.setFilter(t -> t.matches("[0-9]{0,7}"));
		rewardBox.setValue(reward);
		rewardBox.setHint(Component.literal("matter").withStyle(ChatFormatting.DARK_GRAY));
		rewardBox.setResponder(v -> reward = v);
		rewardBox.setTooltip(Tooltip.create(Component.literal("Strange Matter for everyone")));

		String[] caseLabels = new String[cfg.caseIds().size() + 1];
		caseLabels[0] = "No case";
		for (int i = 0; i < cfg.caseIds().size(); i++) {
			caseLabels[i + 1] = cfg.caseNames().get(i);
		}
		caseIndex = Math.min(caseIndex, caseLabels.length - 1);
		addRenderableWidget(new BubbleScreen.Choice(fx, fy + 40, 124, 16, caseLabels, () -> caseIndex, v -> caseIndex = v));
		EditBox casesBox = addRenderableWidget(new EditBox(font, fx + 128, fy + 40, 26, 16, Component.literal("Cases")));
		casesBox.setFilter(t -> t.matches("[0-9]{0,2}"));
		casesBox.setValue(cases);
		casesBox.setResponder(v -> cases = v);
		casesBox.setTooltip(Tooltip.create(Component.literal("How many cases each")));
		BubbleScreen.FlatButton add = addRenderableWidget(new BubbleScreen.FlatButton(fx + rightW() - 100, fy + 40, 100, 16,
				Component.literal(stage == Goals.BONUS ? "Add bonus goal" : "Add objective"), this::add));
		add.color = ACCENT;
	}

	/** Fills the ID box from the game: held item, the mob you're looking at, your dimension, or the next event. */
	private String fillId() {
		var mc = minecraft;
		if (mc == null || mc.player == null) {
			return null;
		}
		return switch (type) {
			case 0 -> {
				Entity e = mc.crosshairPickEntity;
				yield e == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
			}
			case 1 -> mc.player.getMainHandItem().isEmpty() ? null : BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).toString();
			case 2 -> mc.player.level().dimension().location().toString();
			case 5 -> {
				int i = Goals.CUSTOM_EVENTS.indexOf(id);
				yield Goals.CUSTOM_EVENTS.get((i + 1) % Goals.CUSTOM_EVENTS.size());
			}
			default -> null;
		};
	}

	private static int num(String s, int fallback) {
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private void add() {
		Goals.Config cfg = ClientState.goalsConfig;
		if (cfg == null || id.isBlank()) {
			return;
		}
		String t = Goals.TYPES.get(type);
		String clean = id.strip().toLowerCase();
		if (!clean.contains(":") && type != 5) {
			clean = "minecraft:" + clean;
		}
		int n = Math.max(1, num(count, 1));
		JsonObject o = new JsonObject();
		o.addProperty("type", t);
		o.addProperty("id", clean);
		o.addProperty("count", n);
		o.addProperty("text", text.isBlank() ? autoText(type, clean, n) : text.strip());
		o.addProperty("reward", Math.max(0, num(reward, 0)));
		if (caseIndex > 0) {
			o.addProperty("case", cfg.caseIds().get(caseIndex - 1));
			o.addProperty("cases", Math.max(1, num(cases, 1)));
		}
		ClientPlayNetworking.send(new Goals.Edit(Goals.EDIT_ADD_OBJECTIVE, modeId(), stage, 0, o.toString()));
		text = "";
		scroll = Integer.MAX_VALUE; // jump to the bottom where it lands
	}

	/** "Kill 5 Blazes"-style text from the game's own names. */
	private String autoText(int t, String rawId, int n) {
		ResourceLocation rl = ResourceLocation.tryParse(rawId);
		String amount = n > 1 ? n + " " : "";
		return switch (t) {
			case 0 -> "Kill " + amount + (rl != null && BuiltInRegistries.ENTITY_TYPE.containsKey(rl)
					? BuiltInRegistries.ENTITY_TYPE.get(rl).getDescription().getString() : pretty(rawId));
			case 1 -> (n > 1 ? "Collect " + n + " " : "Get ") + (rl != null && BuiltInRegistries.ITEM.containsKey(rl)
					? BuiltInRegistries.ITEM.get(rl).getDescription().getString() : pretty(rawId));
			case 2 -> "Enter " + pretty(rawId);
			case 3 -> {
				var adv = minecraft != null && minecraft.getConnection() != null && rl != null ? minecraft.getConnection().getAdvancements().get(rl) : null;
				yield adv != null && adv.value().display().isPresent() ? adv.value().display().get().getTitle().getString() : pretty(rawId);
			}
			case 4 -> "Find " + pretty(rawId);
			default -> switch (rawId) {
				case "case_opened" -> n > 1 ? "Open " + n + " cases" : "Open a case";
				case "lucky_block" -> "Break " + (n > 1 ? n + " lucky blocks" : "a lucky block");
				case "apothic_roll" -> "Use " + (n > 1 ? n + " Apothic rolls" : "an Apothic roll");
				default -> rawId;
			};
		};
	}

	/** minecraft:ancient_city -> Ancient City */
	private static String pretty(String rawId) {
		String path = rawId.contains(":") ? rawId.substring(rawId.indexOf(':') + 1) : rawId;
		path = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
		StringBuilder b = new StringBuilder();
		for (String w : path.split("_")) {
			if (!w.isEmpty()) {
				b.append(b.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
			}
		}
		return b.toString();
	}

	@Override
	protected boolean clicked(double mx, double my, int button) {
		if (super.clicked(mx, my, button)) {
			return true;
		}
		JsonArray st = stages();
		for (int i = 0; i <= st.size(); i++) {
			int y = stageRowY(i);
			if (mx >= left + 14 && mx < left + 14 + LIST_W && my >= y && my < y + 18 && y + 18 < top + H - 74) {
				int s = i == st.size() ? Goals.BONUS : i;
				if (s != stage) {
					stage = s;
					scroll = 0;
					rebuildWidgets();
				}
				return true;
			}
		}
		return false;
	}

	@Override
	protected boolean scrolled(double mx, double my, double h, double v) {
		if (mx >= rightX() && my >= listTop() && my < formY()) {
			scroll = Math.max(0, scroll - (int) Math.signum(v));
			rebuildWidgets();
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
		BubbleScreen.bigText(ctx, font, "Edit objectives", left + 20, top + 14);
		Goals.Config cfg = ClientState.goalsConfig;
		if (cfg == null || modeIds.isEmpty()) {
			ctx.drawString(font, "Loading...", left + 20, top + 60, MUTED, false);
			return;
		}
		String run = modes.has(cfg.runMode()) ? modes.getAsJsonObject(cfg.runMode()).get("name").getAsString() : cfg.runMode();
		ctx.drawString(font, "This run: " + run + (modeId().equals(cfg.runMode()) ? "  (editing it)" : ""), left + 20, top + 36, MUTED, false);

		// stages
		JsonArray st = stages();
		for (int i = 0; i <= st.size(); i++) {
			int y = stageRowY(i);
			if (y + 18 >= top + H - 74) {
				break;
			}
			boolean bonus = i == st.size();
			boolean sel = bonus ? stage == Goals.BONUS : stage == i;
			boolean hover = mouseX >= left + 14 && mouseX < left + 14 + LIST_W && mouseY >= y && mouseY < y + 18;
			roundRect(ctx, left + 14, y, LIST_W, 18, 5, sel ? 0xFF253029 : hover ? CARD_HOVER : CARD);
			if (sel) {
				roundRect(ctx, left + 14, y + 4, 2, 10, 1, ACCENT);
			}
			String label;
			int n;
			if (bonus) {
				JsonObject m = mode();
				n = m != null && m.has("subgoals") ? m.getAsJsonArray("subgoals").size() : 0;
				label = "★ Bonus goals";
			} else {
				JsonObject s = st.get(i).getAsJsonObject();
				n = s.has("objectives") ? s.getAsJsonArray("objectives").size() : 0;
				label = (i + 1) + ". " + s.get("name").getAsString();
			}
			String cnt = Integer.toString(n);
			ctx.drawString(font, font.plainSubstrByWidth(label, LIST_W - 26), left + 22, y + 5, sel ? TEXT : MUTED, false);
			ctx.drawString(font, cnt, left + 14 + LIST_W - 8 - font.width(cnt), y + 5, n == 0 && !bonus ? DANGER : MUTED, false);
		}

		// objectives
		String heading = stage == Goals.BONUS ? "Bonus goals (any time, any order)"
				: stage < st.size() ? st.get(stage).getAsJsonObject().get("name").getAsString() + " (all of these clear the stage)" : "";
		ctx.drawString(font, font.plainSubstrByWidth(heading, rightW()), rightX(), top + 60, 0xFFE8C46A, false);
		JsonArray obj = objectives();
		if (obj.isEmpty()) {
			ctx.drawString(font, stage == Goals.BONUS ? "No bonus goals." : "Empty: the run waits here until you add an objective.", rightX(), listTop() + 4, MUTED, false);
		}
		for (int i = scroll; i < obj.size() && i < scroll + visibleRows(); i++) {
			JsonObject o = obj.get(i).getAsJsonObject();
			int ry = listTop() + (i - scroll) * ROW_H;
			roundRect(ctx, rightX(), ry, rightW(), ROW_H - 3, 5, CARD);
			ctx.drawString(font, font.plainSubstrByWidth(o.get("text").getAsString(), rightW() - 40), rightX() + 8, ry + 4, TEXT, false);
			ctx.drawString(font, font.plainSubstrByWidth(detail(o, cfg), rightW() - 40), rightX() + 8, ry + 14, MUTED, false);
		}
		if (obj.size() > visibleRows()) {
			String more = (scroll + 1) + "-" + Math.min(obj.size(), scroll + visibleRows()) + " of " + obj.size() + " (scroll)";
			ctx.drawString(font, more, rightX() + rightW() - font.width(more), top + 60, MUTED, false);
		}

		// form
		ctx.fill(rightX(), formY(), rightX() + rightW(), formY() + 1, 0x40FFFFFF);
		ctx.drawString(font, stage == Goals.BONUS ? "NEW BONUS GOAL" : "NEW OBJECTIVE", rightX(), formY() + 4, ACCENT, false);
		ctx.drawString(font, "each", rightX() + 158, formY() + 58, MUTED, false);
		ctx.drawString(font, "STAGES", left + 14, top + H - 82, ACCENT, false);
		ctx.drawString(font, "Saved to config/bubble-goals.json; applies right away.", left + 20, top + H - 24, MUTED, false);
	}

	/** "kill · minecraft:wither ×2 · 150 matter · 1 Weapon Case" */
	private static String detail(JsonObject o, Goals.Config cfg) {
		StringBuilder b = new StringBuilder(o.get("type").getAsString());
		List<String> ids = new ArrayList<>();
		for (String key : new String[]{"id", "ids"}) {
			if (o.has(key)) {
				JsonElement v = o.get(key);
				if (v.isJsonArray()) {
					v.getAsJsonArray().forEach(x -> ids.add(x.getAsString()));
				} else {
					ids.add(v.getAsString());
				}
			}
		}
		b.append(" · ").append(ids.size() == 1 ? ids.get(0) : ids.size() + " choices");
		if (o.has("count") && o.get("count").getAsInt() > 1) {
			b.append(" ×").append(o.get("count").getAsInt());
		}
		if (o.has("reward") && o.get("reward").getAsInt() > 0) {
			b.append(" · ").append(o.get("reward").getAsInt()).append(" matter");
		}
		if (o.has("case")) {
			String c = o.get("case").getAsString();
			int i = cfg.caseIds().indexOf(c);
			int n = o.has("cases") ? o.get("cases").getAsInt() : 1;
			b.append(" · ").append(n).append(" ").append(i >= 0 ? cfg.caseNames().get(i) : c);
		}
		return b.toString();
	}
}
