package moonahmc.bubble.mixin;

import moonahmc.bubble.client.FancyNames;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Eidolon name colours in the tab list (animated ones move there too). */
@Mixin(PlayerTabOverlay.class)
abstract class TabNameMixin {
	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void bubble$styleName(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
		cir.setReturnValue(FancyNames.restyle(cir.getReturnValue()));
	}
}
