package moonahmc.bubble.client;

import com.mojang.blaze3d.platform.InputConstants;
import moonahmc.bubble.Payloads;
import moonahmc.bubble.ShopPayloads;
import moonahmc.bubble.Bubble;
import moonahmc.bubble.client.net.ClientPlayNetworking;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.lwjgl.glfw.GLFW;

@Mod(value = Bubble.MOD_ID, dist = Dist.CLIENT)
public final class BubbleClient {
	private static KeyMapping openKey;
	private static KeyMapping shopKey;
	private static KeyMapping goalsKey;
	private static KeyMapping videoKey;

	public BubbleClient(IEventBus modBus) {
		ClientSettings.load();
		openKey = new KeyMapping("key.bubble.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "category.bubble");
		InventoryOverlay.quickSellKey = new KeyMapping("key.bubble.quick_sell", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "category.bubble");
		shopKey = new KeyMapping("key.bubble.shop", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "category.bubble");
		goalsKey = new KeyMapping("key.bubble.objectives", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F4, "category.bubble");
		videoKey = new KeyMapping("key.bubble.video", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, "category.bubble");
		modBus.addListener((net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Item e) -> e.register(
				(stack, tint) -> tint == 1 ? 0xFF000000 | moonahmc.bubble.CaseItem.color(stack) : -1, moonahmc.bubble.ModContent.CASE.get()));
		modBus.addListener((RegisterKeyMappingsEvent e) -> {
			e.register(openKey);
			e.register(InventoryOverlay.quickSellKey);
			e.register(shopKey);
			e.register(goalsKey);
			e.register(videoKey);
		});
		modBus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers e) -> {
			e.registerEntityRenderer(moonahmc.bubble.ModContent.RAINBOW_CREEPER.get(), CreeperRenderers.Rainbow::new);
			e.registerEntityRenderer(moonahmc.bubble.ModContent.NUKE_CREEPER.get(), CreeperRenderers.Nuke::new);
			e.registerEntityRenderer(moonahmc.bubble.ModContent.GATLING_SKELETON.get(), net.minecraft.client.renderer.entity.SkeletonRenderer::new);
			e.registerEntityRenderer(moonahmc.bubble.ModContent.WATCHER.get(), net.minecraft.client.renderer.entity.EndermanRenderer::new);
			e.registerEntityRenderer(moonahmc.bubble.ModContent.EVIL_WATCHER.get(), net.minecraft.client.renderer.entity.EndermanRenderer::new);
			e.registerEntityRenderer(moonahmc.bubble.ModContent.JOCKEY_JOCKEY.get(), net.minecraft.client.renderer.entity.SpiderRenderer::new);
			e.registerEntityRenderer(moonahmc.bubble.ModContent.SWARM_ZOMBIE.get(), net.minecraft.client.renderer.entity.ZombieRenderer::new);
			e.registerEntityRenderer(moonahmc.bubble.ModContent.SWARMLING.get(), net.minecraft.client.renderer.entity.ZombieRenderer::new);
			e.registerEntityRenderer(moonahmc.bubble.ModContent.GATLING_ARROW.get(), ctx -> new net.minecraft.client.renderer.entity.ArrowRenderer<moonahmc.bubble.GatlingArrow>(ctx) {
				@Override
				public net.minecraft.resources.ResourceLocation getTextureLocation(moonahmc.bubble.GatlingArrow arrow) {
					return net.minecraft.resources.ResourceLocation.withDefaultNamespace("textures/entity/projectiles/arrow.png");
				}
			});
		});
		var bus = NeoForge.EVENT_BUS;
		ClientPlayNetworking.registerGlobalReceiver(moonahmc.bubble.Gamble.CaseList.ID, (payload, context) -> context.client().execute(() -> {
			ClientState.caseList = payload;
			if (context.client().screen instanceof CaseEditorScreen ce) {
				ce.refresh();
			}
		}));
		ClientPlayNetworking.registerGlobalReceiver(moonahmc.bubble.WheelAdmin.Data.ID, (payload, context) -> context.client().execute(() -> {
			ClientState.wheelAdmin = payload.json();
			if (context.client().screen instanceof WheelAdminScreen ws) {
				ws.refresh();
			}
		}));
		ClientPlayNetworking.registerGlobalReceiver(moonahmc.bubble.Goals.Config.ID, (payload, context) -> context.client().execute(() -> {
			ClientState.goalsConfig = payload;
			if (context.client().screen instanceof GoalsEditorScreen ge) {
				ge.refresh();
			}
		}));
		ClientPlayNetworking.registerGlobalReceiver(moonahmc.bubble.Goals.State.ID, (payload, context) -> context.client().execute(() -> {
			GoalsHud.update(ClientState.goals, payload);
			ClientState.goals = payload;
		}));
		bus.addListener((net.neoforged.neoforge.client.event.RenderGuiEvent.Post e) -> GoalsHud.render(e.getGuiGraphics()));
		bus.addListener((net.neoforged.neoforge.client.event.RenderGuiEvent.Post e) -> MatterPopups.renderHud(e.getGuiGraphics()));
		ClientPlayNetworking.registerGlobalReceiver(moonahmc.bubble.Eidolons.State.ID, (payload, context) -> context.client().execute(() -> {
			ClientState.eidolons = payload;
			if (context.client().screen instanceof EidolonScreen es) {
				es.refresh();
			}
		}));
		ClientPlayNetworking.registerGlobalReceiver(moonahmc.bubble.Eidolons.Bets.ID, (payload, context) -> context.client().execute(() -> {
			ClientState.bets = payload;
			if (context.client().screen instanceof ReadyScreen rs) {
				rs.refresh();
			}
		}));
		ClientPlayNetworking.registerGlobalReceiver(moonahmc.bubble.Eidolons.Titles.ID, (payload, context) -> context.client().execute(() -> {
			moonahmc.bubble.Eidolons.applyTitles(payload.text(), payload.color());
			if (context.client().level != null) {
				context.client().level.players().forEach(net.minecraft.world.entity.player.Player::refreshDisplayName);
			}
		}));
		ClientPlayNetworking.registerGlobalReceiver(moonahmc.bubble.Lobby.State.ID, (payload, context) -> context.client().execute(() -> {
			boolean wasActive = ClientState.lobby != null && ClientState.lobby.active();
			ReadyScreen.sounds(ClientState.lobby, payload);
			ClientState.lobby = payload;
			if (context.client().screen instanceof ReadyScreen rs) {
				rs.refresh();
			} else if (payload.active() && !wasActive && context.client().screen == null) {
				context.client().setScreen(new ReadyScreen());
			}
		}));
		bus.addListener((net.neoforged.neoforge.client.event.RenderGuiEvent.Post e) -> ReadyScreen.renderHud(e.getGuiGraphics()));
		ClientPlayNetworking.registerGlobalReceiver(moonahmc.bubble.Gamble.Roll.ID, (payload, context) -> context.client().execute(() -> {
			var current = context.client().screen;
			var back = current instanceof CaseScreen cs ? cs.parent() : current;
			context.client().setScreen(new CaseScreen(payload, back));
		}));
		ClientPlayNetworking.registerGlobalReceiver(moonahmc.bubble.Wheel.Spin.ID, (payload, context) -> context.client().execute(() -> WheelOverlay.start(payload)));
		ClientPlayNetworking.registerGlobalReceiver(moonahmc.bubble.Fx.Start.ID, (payload, context) -> context.client().execute(() -> ClientFx.start(payload)));
		ClientPlayNetworking.registerGlobalReceiver(moonahmc.bubble.VideoShare.Start.ID, (payload, context) -> context.client().execute(() -> VideoOverlay.start(payload)));
		bus.addListener((net.neoforged.neoforge.client.event.RenderGuiEvent.Post e) -> VideoOverlay.render(e.getGuiGraphics()));
		JoinHelp.register(); // Rejoin / Leave on screens where joining hangs
		bus.addListener((net.neoforged.neoforge.client.event.ViewportEvent.ComputeCameraAngles e) -> e.setRoll(e.getRoll() + ClientFx.roll()));
		// Upside down: A/D would move the opposite way to what you see, so swap them (the mouse is flipped in EntityTurnMixin)
		bus.addListener((net.neoforged.neoforge.client.event.MovementInputUpdateEvent e) -> {
			if (ClientFx.flipped()) {
				var in = e.getInput();
				in.leftImpulse = -in.leftImpulse;
				boolean left = in.left;
				in.left = in.right;
				in.right = left;
			}
		});
		bus.addListener((net.neoforged.neoforge.client.event.RenderGuiEvent.Post e) -> WheelOverlay.render(e.getGuiGraphics()));
		ClientPlayNetworking.registerGlobalReceiver(moonahmc.bubble.PregamePayloads.State.ID, (payload, context) -> context.client().execute(() -> {
			ClientState.pregame = payload;
			if (context.client().screen instanceof BubbleScreen screen) {
				screen.pregameChanged();
			}
		}));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.State.ID, (payload, context) -> context.client().execute(() -> {
			ClientState.update(payload);
			if (context.client().screen instanceof BubbleScreen screen) {
				screen.syncFromServer();
			}
		}));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Open.ID, (payload, context) -> context.client().execute(
				() -> context.client().setScreen(new BubbleScreen(context.client().screen))));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Field.ID, (payload, context) -> context.client().execute(
				() -> ClientState.field(payload)));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.PostGame.ID, (payload, context) -> context.client().execute(() -> {
			ClientState.postGame = payload;
			ClientState.fieldActive = false;
			ServerData info = context.client().getCurrentServer();
			if (info != null) {
				ClientState.lastServer = info;
			}
			if (!(context.client().screen instanceof PostGameScreen)) {
				context.client().setScreen(new PostGameScreen());
			}
		}));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.PostStatus.ID, (payload, context) -> context.client().execute(
				() -> ClientState.postStatus = payload));
		ClientPlayNetworking.registerGlobalReceiver(moonahmc.bubble.CurrencyAdmin.ListRows.ID, (payload, context) -> context.client().execute(() -> {
			CurrencyAdminScreen.rows = payload.rows();
			if (context.client().screen instanceof CurrencyAdminScreen s) {
				s.refresh();
			}
		}));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.DownCam.ID, (payload, context) -> context.client().execute(() -> DownCam.start(payload.clip())));
		bus.addListener((net.neoforged.neoforge.client.event.RenderGuiEvent.Post e) -> DownCam.renderHud(e.getGuiGraphics()));
		// While your down cam plays, your own body is hidden (the replay stands in for it)
		bus.addListener((net.neoforged.neoforge.client.event.RenderPlayerEvent.Pre e) -> {
			if (DownCam.active() && e.getEntity() == Minecraft.getInstance().player) {
				e.setCanceled(true);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Killcam.ID, (payload, context) -> context.client().execute(() -> {
			DownCam.stop();
			ClientState.fieldActive = false;
			KillcamReplay.start(payload);
		}));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.PostGameEnd.ID, (payload, context) -> context.client().execute(() -> {
			ClientState.postGame = null;
			ClientState.postStatus = null;
			ClientState.lastServer = null;
			KillcamReplay.stop();
			if (context.client().screen instanceof PostGameScreen) {
				context.client().setScreen(null);
			}
		}));
		// Strange Matter + shop
		ClientPlayNetworking.registerGlobalReceiver(ShopPayloads.Balance.ID, (payload, context) -> context.client().execute(() -> {
			if (ClientState.hasBalance) {
				MatterPopups.changed(ClientState.balance, payload.matter());
			}
			ClientState.hasBalance = true;
			ClientState.balance = payload.matter();
		}));
		ClientPlayNetworking.registerGlobalReceiver(ShopPayloads.Catalog.ID, (payload, context) -> context.client().execute(() -> {
			ClientState.catalog = payload;
			if (context.client().screen instanceof ShopScreen shop) {
				shop.refresh();
			} else if (context.client().screen instanceof BubbleScreen bs) {
				bs.refreshAdmin();
			}
		}));
		ClientPlayNetworking.registerGlobalReceiver(ShopPayloads.ChargeList.ID, (payload, context) -> context.client().execute(
				() -> ClientState.charges = payload));
		ClientPlayNetworking.registerGlobalReceiver(ShopPayloads.QuoteResult.ID, (payload, context) -> context.client().execute(
				() -> ClientState.quote = payload));
		ClientPlayNetworking.registerGlobalReceiver(ShopPayloads.WorthResult.ID, (payload, context) -> context.client().execute(
				() -> SellTooltip.answered(payload)));
		// Revive: who's down (signs over heads, HUD) and the settings for the Bubble menu
		ClientPlayNetworking.registerGlobalReceiver(moonahmc.bubble.Revive.Downed.ID, (payload, context) -> context.client().execute(
				() -> ReviveClient.setDowned(payload.list())));
		ReviveClient.registerControls(bus);
		ClientPlayNetworking.registerGlobalReceiver(moonahmc.bubble.Revive.Settings.ID, (payload, context) -> context.client().execute(() -> {
			ReviveClient.settings = payload;
			if (context.client().screen instanceof BubbleScreen bs) {
				bs.syncFromServer();
			}
		}));
		bus.addListener((net.neoforged.neoforge.client.event.RenderGuiEvent.Post e) -> ReviveClient.renderHud(e.getGuiGraphics()));
		ClientPlayNetworking.registerGlobalReceiver(moonahmc.bubble.Eidolons.Ribbons.ID, (payload, context) -> context.client().execute(() -> {
			RibbonTrails.setWorn(payload.worn());
			if (context.client().screen instanceof EidolonScreen es) {
				es.refresh();
			}
		}));
		bus.addListener((ClientTickEvent.Post e) -> RibbonTrails.tick());
		ClientPlayNetworking.registerGlobalReceiver(moonahmc.bubble.Notice.ID, (payload, context) -> context.client().execute(
				() -> NoticeToast.show(payload.title(), payload.body(), payload.ok())));
		SellTooltip.register(); // "Sells for ..." on your own items' tooltips
		bus.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> ClientState.lastServer = null);
		bus.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
			ClientState.reset();
			SellTooltip.forget();
			ReviveClient.reset();
			RibbonTrails.reset();
			Minecraft.getInstance().execute(DownCam::stop);
			WheelOverlay.stop();
			ClientFx.stop();
			Minecraft.getInstance().execute(VideoOverlay::stop);
			Minecraft.getInstance().execute(KillcamReplay::stop);
		});

		bus.addListener((ClientTickEvent.Post e) -> {
			Minecraft client = Minecraft.getInstance();
			ClientState.tickField();
			KillcamReplay.tick(client);
			DownCam.tick(client);
			while (openKey.consumeClick()) {
				if (client.player == null) {
					continue;
				}
				if (ClientState.lobby != null && ClientState.lobby.active()) {
					client.setScreen(new ReadyScreen());
				} else if (ClientState.postGame != null) {
					client.setScreen(new PostGameScreen());
				} else if (ClientState.available) {
					client.setScreen(new BubbleScreen(null));
				} else {
					client.player.displayClientMessage(Component.literal("This server isn't running Bubble."), true);
				}
			}
			while (goalsKey.consumeClick()) {
				GoalsHud.toggle();
			}
			while (videoKey.consumeClick()) {
				if (VideoOverlay.playing()) {
					VideoOverlay.toggleHidden();
				}
			}
			while (shopKey.consumeClick()) {
				if (client.player != null && ClientState.hasBalance) {
					client.setScreen(new ShopScreen(null));
				}
			}
		});

		bus.addListener((RenderLevelStageEvent e) -> {
			if (e.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
				ForcefieldRenderer.render(e.getPoseStack(), e.getCamera(), e.getPartialTick().getGameTimeDeltaPartialTick(false));
				ReviveClient.renderWorld(e.getPoseStack(), e.getCamera(), e.getPartialTick().getGameTimeDeltaPartialTick(false));
				RibbonTrails.render(e.getPoseStack(), e.getCamera(), e.getPartialTick().getGameTimeDeltaPartialTick(false));
			}
		});
		InventoryOverlay.register(); // Bubble + Shop buttons, balance and the sell slot

		bus.addListener((ScreenEvent.Init.Post e) -> {
			// After a run ends the server restarts with a new world: offer a one-click rejoin.
			Screen screen = e.getScreen();
			if (screen instanceof DisconnectedScreen && ClientState.lastServer != null) {
				ServerData info = ClientState.lastServer;
				Minecraft client = Minecraft.getInstance();
				BubbleScreen.FlatButton rejoin = new BubbleScreen.FlatButton(screen.width / 2 - 100, screen.height - 30, 200, 20,
						Component.literal("Rejoin " + info.name + " (next world)"), () -> rejoin(client, info));
				e.addListener(rejoin);
			}
		});
	}

	/** The key that opens the Bubble menu, as shown in Controls (B by default). */
	static String openKeyName() {
		return openKey == null ? "B" : openKey.getTranslatedKeyMessage().getString();
	}

	private static void rejoin(Minecraft client, ServerData info) {
		ClientState.lastServer = null;
		ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), client, ServerAddress.parseString(info.ip), info, false, null);
	}
}
