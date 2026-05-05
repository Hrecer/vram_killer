package com.vram_killer.client.mixin.atlas;

import com.mojang.blaze3d.textures.GpuTexture;
import com.vram_killer.VRAMKiller;
import com.vram_killer.client.VRAMKillerClient;
import com.vram_killer.client.animation.AnimationTextureOptimizer;
import com.vram_killer.client.atlas.AtlasOptimizer;
import com.vram_killer.client.leak.LeakDetector;
import com.vram_killer.client.scheduler.TextureScheduler;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(TextureAtlas.class)
public class SpriteAtlasTextureMixin {

	@Inject(method = "upload", at = @At("RETURN"))
	private void vram_killer$afterUpload(SpriteLoader.Preparations preparations, CallbackInfo ci) {
		try {
			TextureAtlas self = (TextureAtlas) (Object) this;
			GpuTexture gpuTexture = self.getTexture();

			if (gpuTexture == null) return;

			int atlasWidth = gpuTexture.getWidth(0);
			int atlasHeight = gpuTexture.getHeight(0);
			int mipLevels = gpuTexture.getMipLevels();

			int glTextureId = vram_killer$getBoundTextureId();
			if (glTextureId <= 0) return;

			long estimatedMemory = estimateAtlasMemory(atlasWidth, atlasHeight, mipLevels);

			String path = "atlas:" + gpuTexture.getLabel();

			TextureScheduler scheduler = VRAMKillerClient.getTextureScheduler();
			if (scheduler != null) {
				scheduler.registerTexture(glTextureId, path, atlasWidth, atlasHeight,
					false, false, false, false, true);
			}

			LeakDetector leakDetector = VRAMKillerClient.getVRAMManager().getLeakDetector();
			if (leakDetector != null) {
				leakDetector.trackTextureCreation(glTextureId, atlasWidth, atlasHeight, path);
			}

			VRAMKiller.LOGGER.info("Atlas uploaded: {}x{}x{} ({} MB) id={}",
				atlasWidth, atlasHeight, mipLevels, estimatedMemory / (1024 * 1024), glTextureId);

			AtlasOptimizer optimizer = VRAMKillerClient.getAtlasOptimizer();
			if (optimizer != null && optimizer.isEnabled()) {
				List<SpriteContents> sprites = preparations.regions().values().stream()
					.map(sprite -> sprite.contents())
					.toList();

				if (!sprites.isEmpty()) {
					optimizer.registerAtlas(gpuTexture.getLabel(), atlasWidth, atlasHeight);
					AtlasOptimizer.OptimizedPackResult result = optimizer.optimizeSpritePack(gpuTexture.getLabel(), sprites);

					if (result.success) {
						VRAMKiller.LOGGER.debug("Atlas optimization analysis: {}", result.getSummary());
					}
				}
			}

			AnimationTextureOptimizer animOptimizer = VRAMKillerClient.getAnimationOptimizer();
			if (animOptimizer != null && animOptimizer.isEnabled()) {
				for (var entry : preparations.regions().entrySet()) {
					SpriteContents contents = entry.getValue().contents();
					if (contents != null) {
						animOptimizer.registerAnimationTexture(
							glTextureId, contents, atlasWidth, atlasHeight);
					}
				}
			}

		} catch (Exception e) {
			VRAMKiller.LOGGER.debug("Atlas tracking error: {}", e.getMessage());
		}
	}

	@Unique
	private int vram_killer$getBoundTextureId() {
		try {
			int[] textureId = {0};
			GL11.glGetIntegerv(GL11.GL_TEXTURE_BINDING_2D, textureId);
			return textureId[0];
		} catch (Exception e) {
			return 0;
		}
	}

	private long estimateAtlasMemory(int width, int height, int mipLevels) {
		long total = (long) width * height * 4L;
		for (int i = 1; i < mipLevels; i++) {
			total += (width >> i) * (height >> i) * 4L;
		}
		return total;
	}
}
