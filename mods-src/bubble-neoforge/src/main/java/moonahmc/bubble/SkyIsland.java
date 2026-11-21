package moonahmc.bubble;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The start-of-run wheel's "Sky island start": everyone is sent to the Aether together, onto the nearest island to the
 * middle, with Slow Falling for safety and 14 glowstone and a water bucket each so they can build a portal back down.
 */
final class SkyIsland {
	private static final ResourceKey<Level> AETHER = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("aether", "the_aether"));

	private SkyIsland() {
	}

	static void send(MinecraftServer server) {
		ServerLevel aether = server.getLevel(AETHER);
		if (aether == null) {
			server.getPlayerList().broadcastSystemMessage(Component.literal("🎡 The Aether isn't installed, so everyone stays put.").withStyle(ChatFormatting.GRAY), false);
			return;
		}
		BlockPos spot = findIsland(aether);
		if (spot == null) {
			server.getPlayerList().broadcastSystemMessage(Component.literal("🎡 Couldn't find a sky island to land on, so everyone stays put.").withStyle(ChatFormatting.GRAY), false);
			Bubble.LOG.warn("Sky island start: no island found near the middle of the Aether");
			return;
		}
		int i = 0;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!BubbleServer.counts(p)) {
				continue;
			}
			double dx = (i % 3) - 1, dz = (i / 3 % 3) - 1;
			i++;
			p.stopRiding();
			p.teleportTo(aether, spot.getX() + 0.5 + dx, spot.getY() + 1, spot.getZ() + 0.5 + dz, p.getYRot(), p.getXRot());
			p.resetFallDistance();
			p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 30));
			for (ItemStack s : new ItemStack[] {new ItemStack(Items.GLOWSTONE, 14), new ItemStack(Items.WATER_BUCKET)}) {
				if (!p.getInventory().add(s)) {
					p.drop(s, false);
				}
			}
			p.playNotifySound(SoundEvents.PORTAL_TRAVEL, SoundSource.PLAYERS, 0.4f, 1.2f);
			BubbleServer.title(p, Component.literal("SKY ISLAND").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
					Component.literal("Glowstone + water make a portal home").withStyle(ChatFormatting.GRAY), 5, 60, 15);
		}
	}

	/** Walks out from (0, 0) in chunk-sized steps until it finds solid ground with room to stand. */
	private static BlockPos findIsland(ServerLevel level) {
		for (int ring = 0; ring <= 24; ring++) {
			for (int cx = -ring; cx <= ring; cx++) {
				for (int cz = -ring; cz <= ring; cz++) {
					if (Math.max(Math.abs(cx), Math.abs(cz)) != ring) {
						continue;
					}
					int x = cx * 16 + 8, z = cz * 16 + 8;
					level.getChunk(x >> 4, z >> 4); // makes sure it's generated
					int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
					if (y <= level.getMinBuildHeight() + 4) {
						continue;
					}
					BlockPos ground = new BlockPos(x, y - 1, z);
					var state = level.getBlockState(ground);
					if (state.isFaceSturdy(level, ground, net.minecraft.core.Direction.UP) && level.getFluidState(ground).isEmpty()
							&& level.getBlockState(ground.above()).isAir() && level.getBlockState(ground.above(2)).isAir()) {
						return ground;
					}
				}
			}
		}
		return null;
	}
}
