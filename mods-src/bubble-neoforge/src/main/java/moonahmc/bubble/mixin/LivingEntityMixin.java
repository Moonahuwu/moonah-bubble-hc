package moonahmc.bubble.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import moonahmc.bubble.Fx;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The "Ice ground" wheel slice: while it's on, the local player's ground is as slippery as blue ice. */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
	@ModifyExpressionValue(method = "travel", require = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;getFriction(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)F"))
	private float bubble$iceGround(float friction) {
		if ((Object) this instanceof Player p && p.level().isClientSide && Fx.iceOn()) {
			return Math.max(friction, Fx.ICE);
		}
		return friction;
	}
}
