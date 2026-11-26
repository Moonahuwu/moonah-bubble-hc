package moonahmc.bubble.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fix for Relics' Glitchy Mantle crashing everyone who can see the player when its glitch goes off ("Not building!",
 * relics issues #343 / #356, still in 0.12.11; the real fix is the unmerged relics PR #366). For the glitch mask Relics
 * draws the player a second time, layers too (armour, held items), into a small throwaway buffer that can't take
 * the enchantment glint, so an enchanted item in hand crashes the game. The layers are already drawn normally, so
 * the mask pass simply leaves them out: the glitch still shows on the body. Client only, optional
 * (bubble-compat.mixins.json); skipped without Relics or if it changes.
 */
@Pseudo
@Mixin(targets = "it.hurts.sskirillss.relics.client.post_effects.misc.EntityGlitchMask", remap = false)
abstract class RelicsGlitchFixMixin {
	@Redirect(method = "render", remap = false, require = 0, at = @At(value = "INVOKE", remap = false,
			target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V"))
	@SuppressWarnings("rawtypes")
	private static void bubble$skipMaskLayer(RenderLayer layer, PoseStack pose, MultiBufferSource buffers, int light, Entity entity,
			float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {
		// left out of the mask pass on purpose (see above)
	}
}
