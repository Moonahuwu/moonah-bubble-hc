package moonahmc.bubble.mixin;

import moonahmc.bubble.client.ReplayActors;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a replay plays (down cam, kill cam), the real mobs, players and projectiles aren't drawn: the replay's
 * stand-ins show where they were instead. Only on this screen, and only until the replay stops.
 */
@Mixin(EntityRenderDispatcher.class)
abstract class ReplayHideMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private <E extends Entity> void bubble$hideDuringReplay(E entity, Frustum frustum, double x, double y, double z,
			CallbackInfoReturnable<Boolean> cir) {
		if (ReplayActors.hides(entity)) {
			cir.setReturnValue(false);
		}
	}
}
