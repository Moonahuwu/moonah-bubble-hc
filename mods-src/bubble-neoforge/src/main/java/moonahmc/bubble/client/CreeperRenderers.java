package moonahmc.bubble.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.CreeperRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.monster.Creeper;

import java.io.InputStream;

/**
 * Renderers for the rainbow and nuke creepers. Both use a greyscale copy of the normal creeper texture (made from the
 * game's own texture when first needed, so resource packs apply) and tint it: cycling rainbow colours, or a slow
 * toxic green-to-yellow pulse for the (bigger) nuke creeper.
 */
final class CreeperRenderers {
	private static final ResourceLocation VANILLA = ResourceLocation.withDefaultNamespace("textures/entity/creeper/creeper.png");
	private static ResourceLocation grey;

	private CreeperRenderers() {
	}

	static ResourceLocation greyTexture() {
		if (grey == null) {
			grey = VANILLA;
			try (InputStream in = Minecraft.getInstance().getResourceManager().open(VANILLA)) {
				NativeImage img = NativeImage.read(in);
				for (int y = 0; y < img.getHeight(); y++) {
					for (int x = 0; x < img.getWidth(); x++) {
						int c = img.getPixelRGBA(x, y); // ABGR
						int a = c >>> 24;
						int r = c & 255;
						int g = (c >> 8) & 255;
						int b = (c >> 16) & 255;
						int l = Math.min(255, 50 + (int) ((0.3 * r + 0.59 * g + 0.11 * b) * 1.5));
						img.setPixelRGBA(x, y, (a << 24) | (l << 16) | (l << 8) | l);
					}
				}
				ResourceLocation id = ResourceLocation.fromNamespaceAndPath("bubble", "dynamic/grey_creeper");
				Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(img));
				grey = id;
			} catch (Exception e) {
				moonahmc.bubble.Bubble.LOG.warn("Could not make the rainbow creeper texture; using the normal one", e);
			}
		}
		return grey;
	}

	static final class Rainbow extends CreeperRenderer {
		Rainbow(EntityRendererProvider.Context ctx) {
			super(ctx);
		}

		@Override
		public ResourceLocation getTextureLocation(Creeper creeper) {
			return greyTexture();
		}

		@Override
		public void render(Creeper creeper, float yaw, float partialTicks, PoseStack pose, MultiBufferSource buffers, int light) {
			float hue = ((creeper.tickCount + partialTicks) * 0.05f + creeper.getId() * 0.137f) % 1f;
			super.render(creeper, yaw, partialTicks, pose, tinted(buffers, Mth.hsvToRgb(hue, 0.8f, 1f)), light);
		}
	}

	static final class Nuke extends CreeperRenderer {
		Nuke(EntityRendererProvider.Context ctx) {
			super(ctx);
			shadowRadius = 0.7f;
		}

		@Override
		public ResourceLocation getTextureLocation(Creeper creeper) {
			return greyTexture();
		}

		@Override
		protected void scale(Creeper creeper, PoseStack pose, float partialTicks) {
			super.scale(creeper, pose, partialTicks);
			pose.scale(1.4f, 1.4f, 1.4f);
		}

		@Override
		public void render(Creeper creeper, float yaw, float partialTicks, PoseStack pose, MultiBufferSource buffers, int light) {
			boolean unstable = creeper instanceof moonahmc.bubble.NukeCreeper nc && nc.isUnstable();
			float t = (Mth.sin((creeper.tickCount + partialTicks) * (unstable ? 0.6f : 0.08f)) + 1) / 2;
			int r = unstable ? (int) Mth.lerp(t, 0x90, 0xFF) : (int) Mth.lerp(t, 0x4A, 0xF5);
			int g = unstable ? (int) Mth.lerp(t, 0x10, 0x50) : (int) Mth.lerp(t, 0x7A, 0xE0);
			int b = unstable ? 0x10 : (int) Mth.lerp(t, 0x10, 0x20);
			super.render(creeper, yaw, partialTicks, pose, tinted(buffers, (r << 16) | (g << 8) | b), light);
		}
	}

	private static MultiBufferSource tinted(MultiBufferSource buffers, int rgb) {
		int tr = (rgb >> 16) & 255;
		int tg = (rgb >> 8) & 255;
		int tb = rgb & 255;
		return type -> new Tint(buffers.getBuffer(type), tr, tg, tb);
	}

	/** Multiplies every vertex colour by a tint. */
	private record Tint(VertexConsumer inner, int tr, int tg, int tb) implements VertexConsumer {
		private int mul(int argb) {
			int a = argb >>> 24;
			int r = ((argb >> 16) & 255) * tr / 255;
			int g = ((argb >> 8) & 255) * tg / 255;
			int b = (argb & 255) * tb / 255;
			return (a << 24) | (r << 16) | (g << 8) | b;
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			inner.addVertex(x, y, z);
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			inner.setColor(r * tr / 255, g * tg / 255, b * tb / 255, a);
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			inner.setUv(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			inner.setUv1(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			inner.setUv2(u, v);
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			inner.setNormal(x, y, z);
			return this;
		}

		@Override
		public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
			inner.addVertex(x, y, z, mul(color), u, v, overlay, light, nx, ny, nz);
		}
	}
}
