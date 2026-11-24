package moonahmc.bubble.client;

import moonahmc.bubble.Eidolons;
import moonahmc.bubble.client.net.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

import static moonahmc.bubble.client.BubbleScreen.CARD;
import static moonahmc.bubble.client.BubbleScreen.MUTED;
import static moonahmc.bubble.client.BubbleScreen.TEXT;
import static moonahmc.bubble.client.BubbleScreen.panel;
import static moonahmc.bubble.client.BubbleScreen.roundRect;

/** Spend Eidolons: permanent upgrades, cosmetics (trails and titles), and the hall of fame. */
public final class EidolonScreen extends ScaledScreen {
	static final ResourceLocation ICON = ResourceLocation.fromNamespaceAndPath("bubble", "textures/gui/eidolon.png");
	private static final int W = 440;
	private static final int H = 300;
	private static final int PURPLE = 0xFFD36BFF;
	private static int tab;
	private final Screen parent;
	private int left;
	private int top;

	EidolonScreen(Screen parent) {
		super(Component.literal("Eidolons"));
		this.parent = parent;
		ClientPlayNetworking.send(new Eidolons.Action(Eidolons.REQUEST, ""));
	}

	@Override
	protected int panelWidth() {
		return W;
	}

	@Override
	protected int panelHeight() {
		return H;
	}

	static void icon(GuiGraphics ctx, int x, int y, int size) {
		com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
		ctx.blit(ICON, x, y, size, size, 0, 0, 64, 64, 64, 64);
		com.mojang.blaze3d.systems.RenderSystem.disableBlend();
	}

	void refresh() {
		rebuildWidgets();
	}

	@Override
	protected void init() {
		fit();
		left = (width - W) / 2;
		top = (height - H) / 2;
		String[] tabs = {"Upgrades", "Cosmetics", "Trails", "Hall of Fame"};
		int tx = left + 18;
		for (int i = 0; i < tabs.length; i++) {
			int index = i;
			int w = font.width(tabs[i]) + 16;
			BubbleScreen.FlatButton b = addRenderableWidget(new BubbleScreen.FlatButton(tx, top + 40, w, 16, Component.literal(tabs[i]), () -> {
				tab = index;
				rebuildWidgets();
			}));
			b.ghost = tab != i;
			b.selected = tab == i;
			b.color = PURPLE;
			tx += w + 4;
		}
		addRenderableWidget(new BubbleScreen.FlatButton(left + W - 14 - 64, top + H - 30, 64, 20, Component.literal("Back"), this::onClose)).ghost = true;
		Eidolons.State st = ClientState.eidolons;
		if (st == null) {
			return;
		}
		if (tab == 0) {
			for (int i = 0; i < Eidolons.UPGRADES.size(); i++) {
				Eidolons.Upgrade u = Eidolons.UPGRADES.get(i);
				int lvl = st.upgrades().getOrDefault(u.id(), 0);
				boolean max = lvl >= u.costs().length;
				BubbleScreen.FlatButton b = addRenderableWidget(new BubbleScreen.FlatButton(left + W - 28 - 90, rowY(i) + 8, 90, 18,
						Component.literal(max ? "Maxed" : "Buy  " + u.costs()[lvl]),
						() -> ClientPlayNetworking.send(new Eidolons.Action(Eidolons.BUY_UPGRADE, u.id()))));
				b.active = !max && st.eidolons() >= (max ? 0 : u.costs()[lvl]);
				b.color = PURPLE;
			}
		} else if (tab == 1) {
			int[] counts = new int[2];
			for (Eidolons.Cosmetic c : Eidolons.COSMETICS) {
				if (c.kind().equals("ribbon")) {
					continue;
				}
				int col = c.kind().equals("trail") ? 0 : 1;
				int x = left + 18 + col * ((W - 36) / 2);
				int y = top + 82 + counts[col]++ * 22;
				boolean owned = st.owned().contains(c.id());
				boolean equipped = c.id().equals(st.trail()) || c.id().equals(st.title());
				BubbleScreen.FlatButton b = addRenderableWidget(new BubbleScreen.FlatButton(x + (W - 36) / 2 - 8 - 70, y + 2, 70, 16,
						Component.literal(equipped ? "Equipped" : owned ? "Equip" : "Buy  " + c.cost()), () -> {
					if (!owned) {
						ClientPlayNetworking.send(new Eidolons.Action(Eidolons.BUY_COSMETIC, c.id()));
					} else if (!equipped) {
						ClientPlayNetworking.send(new Eidolons.Action(Eidolons.EQUIP, c.id()));
					} else {
						ClientPlayNetworking.send(new Eidolons.Action(c.kind().equals("trail") ? Eidolons.UNEQUIP_TRAIL : Eidolons.UNEQUIP_TITLE, c.id()));
					}
				}));
				b.ghost = equipped;
				b.color = PURPLE;
				b.active = owned || st.eidolons() >= c.cost();
				if (equipped) {
					b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Click to take it off")));
				}
			}
		} else if (tab == 2) {
			String mine = minecraft != null && minecraft.player != null ? RibbonTrails.worn.get(minecraft.player.getUUID()) : null;
			List<Eidolons.Cosmetic> ribbons = ribbons();
			for (int i = 0; i < ribbons.size(); i++) {
				Eidolons.Cosmetic c = ribbons.get(i);
				int x = ribbonX(i);
				int y = ribbonY(i);
				boolean owned = st.owned().contains(c.id());
				boolean equipped = c.id().equals(mine);
				BubbleScreen.FlatButton b = addRenderableWidget(new BubbleScreen.FlatButton(x + ribbonW() - 8 - 70, y + 3, 70, 14,
						Component.literal(equipped ? "Equipped" : owned ? "Equip" : "Buy  " + c.cost()), () -> {
					if (!owned) {
						ClientPlayNetworking.send(new Eidolons.Action(Eidolons.BUY_COSMETIC, c.id()));
					} else if (!equipped) {
						ClientPlayNetworking.send(new Eidolons.Action(Eidolons.EQUIP, c.id()));
					} else {
						ClientPlayNetworking.send(new Eidolons.Action(Eidolons.UNEQUIP_RIBBON, c.id()));
					}
				}));
				b.ghost = equipped;
				b.color = PURPLE;
				b.active = owned || st.eidolons() >= c.cost();
				if (equipped) {
					b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Click to take it off")));
				}
			}
		}
	}

	private static List<Eidolons.Cosmetic> ribbons() {
		return Eidolons.COSMETICS.stream().filter(c -> c.kind().equals("ribbon")).toList();
	}

	private int ribbonW() {
		return (W - 36) / 2 - 4;
	}

	private int ribbonX(int i) {
		return left + 18 + (i % 2) * ((W - 36) / 2);
	}

	private int ribbonY(int i) {
		return top + 80 + (i / 2) * 37;
	}

	/** The ribbon's texture, repeated along a strip like it looks behind you. */
	private static void preview(GuiGraphics ctx, String id, int x, int y, int w, int h) {
		ResourceLocation tex = ResourceLocation.fromNamespaceAndPath(moonahmc.bubble.Bubble.MOD_ID, "textures/trail/" + id + ".png");
		com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
		ctx.enableScissor(x, y, x + w, y + h);
		for (int px = x; px < x + w; px += h * 4) {
			ctx.blit(tex, px, y, h * 4, h, 0, 0, 128, 32, 128, 32);
		}
		ctx.disableScissor();
		com.mojang.blaze3d.systems.RenderSystem.disableBlend();
	}

	private int rowY(int i) {
		return top + 64 + i * 38;
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
		BubbleScreen.bigText(ctx, font, "Eidolons", left + 22, top + 14);
		Eidolons.State st = ClientState.eidolons;
		String bal = st == null ? "..." : String.format("%,d", st.eidolons());
		ctx.pose().pushPose();
		ctx.pose().translate(left + W - 22, top + 16, 0);
		ctx.pose().scale(1.4f, 1.4f, 1);
		int w = font.width(bal);
		ctx.drawString(font, bal, -w, 0, PURPLE, false);
		icon(ctx, -w - 13, -2, 11);
		ctx.pose().popPose();
		String note = "Earned when a run ends: 1 per 100 Strange Matter, 2 per stage, 10 for a victory. They never reset.";
		ctx.drawString(font, font.plainSubstrByWidth(note, W - 40), left + 22, top + H - 24, MUTED, false);
		if (st == null) {
			return;
		}
		if (tab == 0) {
			for (int i = 0; i < Eidolons.UPGRADES.size(); i++) {
				Eidolons.Upgrade u = Eidolons.UPGRADES.get(i);
				int y = rowY(i);
				int lvl = st.upgrades().getOrDefault(u.id(), 0);
				roundRect(ctx, left + 14, y, W - 28, 34, 6, CARD);
				ctx.drawString(font, u.name(), left + 24, y + 6, TEXT, false);
				ctx.drawString(font, u.desc(), left + 24, y + 19, MUTED, false);
				// level pips
				int px = left + 24 + font.width(u.name()) + 8;
				for (int k = 0; k < u.costs().length; k++) {
					ctx.fill(px + k * 8, y + 7, px + k * 8 + 6, y + 13, k < lvl ? PURPLE : 0xFF3A3F48);
				}
			}
		} else if (tab == 1) {
			ctx.drawString(font, "PARTICLE TRAILS", left + 22, top + 68, PURPLE, false);
			ctx.drawString(font, "TITLES (above your head and in chat)", left + 18 + (W - 36) / 2 + 4, top + 68, PURPLE, false);
			int[] counts = new int[2];
			for (Eidolons.Cosmetic c : Eidolons.COSMETICS) {
				if (c.kind().equals("ribbon")) {
					continue;
				}
				int col = c.kind().equals("trail") ? 0 : 1;
				int x = left + 18 + col * ((W - 36) / 2);
				int y = top + 82 + counts[col]++ * 22;
				roundRect(ctx, x, y, (W - 36) / 2 - 4, 20, 5, CARD);
				String name = c.kind().equals("title") ? "[" + c.name() + "]" : c.name();
				ctx.drawString(font, name, x + 8, y + 6, 0xFF000000 | c.color(), false);
			}
		} else if (tab == 2) {
			ctx.drawString(font, "TRAILS: a ribbon behind you that fades out (wear one with a particle trail too)", left + 22, top + 66, PURPLE, false);
			List<Eidolons.Cosmetic> ribbons = ribbons();
			for (int i = 0; i < ribbons.size(); i++) {
				Eidolons.Cosmetic c = ribbons.get(i);
				int x = ribbonX(i);
				int y = ribbonY(i);
				roundRect(ctx, x, y, ribbonW(), 33, 5, CARD);
				ctx.drawString(font, c.name(), x + 8, y + 6, 0xFF000000 | c.color(), false);
				preview(ctx, c.id(), x + 8, y + 19, ribbonW() - 16, 10);
			}
		} else {
			int y = top + 66;
			if (st.hall().isEmpty()) {
				ctx.drawString(font, "No records yet. Finish a run!", left + 22, y, MUTED, false);
			}
			for (Eidolons.HallEntry h : st.hall()) {
				roundRect(ctx, left + 14, y, W - 28, 24, 5, CARD);
				ctx.drawString(font, h.label(), left + 22, y + 4, 0xFFF2C14E, false);
				ctx.drawString(font, font.plainSubstrByWidth(h.holder(), 180), left + 22, y + 14, TEXT, false);
				String v = font.plainSubstrByWidth(h.value(), 200);
				ctx.drawString(font, v, left + W - 22 - font.width(v), y + 9, MUTED, false);
				y += 27;
			}
		}
	}
}
