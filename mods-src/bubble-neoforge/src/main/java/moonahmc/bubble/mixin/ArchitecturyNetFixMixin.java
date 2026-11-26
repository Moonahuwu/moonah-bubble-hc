package moonahmc.bubble.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fix for Architectury's packet registry (architectury-api#713): it keeps every mod's packets in plain HashMaps, and
 * NeoForge builds mods at the same time on several threads, so now and then a start-up loses one (FTB Ultimine's,
 * most often). Then every time that mod sends the packet the player is kicked ("codec is null"), until a restart.
 * Swapping the maps for thread-safe ones as soon as the class loads, before any mod registers, stops that.
 * Optional (bubble-compat.mixins.json): without Architectury, or if it changes, this is skipped.
 */
@Pseudo
@Mixin(targets = "dev.architectury.impl.NetworkAggregator", remap = false)
abstract class ArchitecturyNetFixMixin {
	@Shadow @Final @Mutable private static Map<Object, Object> C2S_TYPE;
	@Shadow @Final @Mutable private static Map<Object, Object> S2C_TYPE;
	@Shadow @Final @Mutable private static Map<Object, Object> C2S_RECEIVER;
	@Shadow @Final @Mutable private static Map<Object, Object> S2C_RECEIVER;
	@Shadow @Final @Mutable private static Map<Object, Object> C2S_CODECS;
	@Shadow @Final @Mutable private static Map<Object, Object> S2C_CODECS;
	@Shadow @Final @Mutable private static Map<Object, Object> C2S_TRANSFORMERS;
	@Shadow @Final @Mutable private static Map<Object, Object> S2C_TRANSFORMERS;

	@Inject(method = "<clinit>", at = @At("TAIL"), remap = false)
	private static void bubble$threadSafeMaps(CallbackInfo ci) {
		C2S_TYPE = new ConcurrentHashMap<>(C2S_TYPE);
		S2C_TYPE = new ConcurrentHashMap<>(S2C_TYPE);
		C2S_RECEIVER = new ConcurrentHashMap<>(C2S_RECEIVER);
		S2C_RECEIVER = new ConcurrentHashMap<>(S2C_RECEIVER);
		C2S_CODECS = new ConcurrentHashMap<>(C2S_CODECS);
		S2C_CODECS = new ConcurrentHashMap<>(S2C_CODECS);
		C2S_TRANSFORMERS = new ConcurrentHashMap<>(C2S_TRANSFORMERS);
		S2C_TRANSFORMERS = new ConcurrentHashMap<>(S2C_TRANSFORMERS);
	}
}
