package moonahmc.bubble.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Joining sometimes hangs on "Connecting..." / "Loading terrain..." or fails. After a few seconds on those screens (and
 * straight away on the disconnected screen) there's a Rejoin and a Leave button in the middle of the screen.
 */
public final class JoinHelp {
	private static final long SHOW_AFTER_MS = 5000;
	private static ServerData server;
	private static Screen shownOn;
	private static long shownAt;
	private static Button rejoin;
	private static Button leave;

	private JoinHelp() {
	}

	/** ConnectScreenMixin: someone is connecting to `data`. */
	public static void joining(ServerData data) {
		if (data != null) {
			server = data;
		}
	}

	static void register() {
		var bus = NeoForge.EVENT_BUS;
		bus.addListener((ScreenEvent.Init.Post e) -> {
			Screen s = e.getScreen();
			boolean loading = s instanceof ConnectScreen || s instanceof ReceivingLevelScreen;
			if (server == null || !(loading || s instanceof DisconnectedScreen)) {
				return;
			}
			if (s != shownOn) {
				shownOn = s;
				shownAt = System.currentTimeMillis();
			}
			Minecraft mc = Minecraft.getInstance();
			int cx = s.width / 2;
			ServerData target = server;
			if (s instanceof DisconnectedScreen) {
				// It already has "Back to server list". After a run ends Bubble adds its own "Rejoin (next world)" there.
				rejoin = null;
				leave = null;
				if (ClientState.lastServer == null) {
					e.addListener(Button.builder(Component.literal("Rejoin " + target.name), b -> rejoin(mc, s, target))
							.bounds(cx - 100, s.height - 30, 200, 20).build());
				}
				return;
			}
			int y = s.height / 2 + 30;
			rejoin = Button.builder(Component.literal("Rejoin"), b -> rejoin(mc, s, target)).bounds(cx - 102, y, 100, 20).build();
			leave = Button.builder(Component.literal("Leave"), b -> leave(mc, s)).bounds(cx + 2, y, 100, 20).build();
			rejoin.visible = false; // shown after a few seconds (see Render.Pre)
			leave.visible = false;
			e.addListener(rejoin);
			e.addListener(leave);
		});
		bus.addListener((ScreenEvent.Render.Pre e) -> {
			Screen s = e.getScreen();
			if (s != shownOn || rejoin == null || s instanceof DisconnectedScreen) {
				return;
			}
			boolean show = System.currentTimeMillis() - shownAt > SHOW_AFTER_MS;
			rejoin.visible = show;
			leave.visible = show;
		});
		bus.addListener((ScreenEvent.Render.Post e) -> {
			Screen s = e.getScreen();
			if (s != shownOn || rejoin == null || !rejoin.visible || s instanceof DisconnectedScreen) {
				return;
			}
			GuiGraphics ctx = e.getGuiGraphics();
			Minecraft mc = Minecraft.getInstance();
			String hint = "Taking too long? Rejoin, or leave and try again.";
			ctx.drawString(mc.font, hint, s.width / 2 - mc.font.width(hint) / 2, rejoin.getY() - 12, 0xFFAAAAAA, true);
		});
	}

	/** Cancels a connection that's still being made (ConnectScreen's own Cancel button does the cleanup). */
	private static void cancelConnecting(Screen s) {
		if (!(s instanceof ConnectScreen)) {
			return;
		}
		for (var child : s.children()) {
			if (child instanceof Button b && b != rejoin && b != leave && b.getMessage().equals(CommonComponents.GUI_CANCEL)) {
				b.onPress();
				return;
			}
		}
	}

	private static Screen serverList() {
		return new JoinMultiplayerScreen(new TitleScreen());
	}

	private static void leave(Minecraft mc, Screen s) {
		cancelConnecting(s);
		mc.disconnect(serverList());
	}

	private static void rejoin(Minecraft mc, Screen s, ServerData target) {
		cancelConnecting(s);
		mc.disconnect(serverList());
		ClientState.lastServer = null;
		ConnectScreen.startConnecting(serverList(), mc, ServerAddress.parseString(target.ip), target, false, null);
	}
}
