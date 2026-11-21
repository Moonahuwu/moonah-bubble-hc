package moonahmc.bubble.mixin;

import moonahmc.bubble.client.JoinHelp;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remembers which server you're joining, so the loading screens can offer Rejoin. */
@Mixin(ConnectScreen.class)
abstract class ConnectScreenMixin {
	@Inject(method = "startConnecting", at = @At("HEAD"), require = 0)
	private static void bubble$remember(Screen parent, Minecraft mc, ServerAddress address, ServerData data, boolean quickPlay,
			TransferState transfer, CallbackInfo ci) {
		JoinHelp.joining(data);
	}
}
