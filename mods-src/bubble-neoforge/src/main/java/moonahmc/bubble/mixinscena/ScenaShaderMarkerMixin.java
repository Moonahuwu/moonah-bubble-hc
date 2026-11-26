package moonahmc.bubble.mixinscena;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Empty: only here so client.ScenaShaderFix (this config's plugin) gets to see Sodium's block renderer once Scena's
 * mixin has been merged into it, and can fix Scena's shader hook there.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.frapi.render.AbstractBlockRenderContext", remap = false, priority = 900)
abstract class ScenaShaderMarkerMixin {
}
