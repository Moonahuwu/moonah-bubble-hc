package moonahmc.bubble.mixin;

import moonahmc.bubble.client.ClientFx;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * "Upside down" wheel slice: the camera is rolled 180 degrees, which on its own makes the mouse feel backwards (right
 * pans left, up looks down). Flip the mouse for the local player while the view is upside down, so it feels normal.
 */
@Mixin(Entity.class)
abstract class EntityTurnMixin {
	@ModifyVariable(method = "turn", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
	private double bubble$flipYaw(double yRot) {
		return flipped() ? -yRot : yRot;
	}

	@ModifyVariable(method = "turn", at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
	private double bubble$flipPitch(double xRot) {
		return flipped() ? -xRot : xRot;
	}

	private boolean flipped() {
		return (Object) this == Minecraft.getInstance().player && ClientFx.flipped();
	}
}
