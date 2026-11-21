package moonahmc.bubble.mixin;

import moonahmc.bubble.BubbleServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs Bubble's death handling once the player has finished dying (drops, death message, stats), like Fabric's
 * AFTER_DEATH event. NeoForge's LivingDeathEvent fires before all that, which is where the inventory is kept instead.
 */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
	@Inject(method = "die", at = @At("TAIL"))
	private void bubble$afterDeath(DamageSource source, CallbackInfo ci) {
		BubbleServer.afterDeath((ServerPlayer) (Object) this, source);
	}
}
