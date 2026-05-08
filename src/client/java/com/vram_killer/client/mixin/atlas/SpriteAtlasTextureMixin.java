package com.vram_killer.client.mixin.atlas;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import com.vram_killer.VRAMKiller;
import com.vram_killer.client.VRAMKillerClient;
import com.vram_killer.client.VRAMManager;
import com.vram_killer.client.animation.AnimationTextureOptimizer;
import com.vram_killer.client.atlas.AtlasOptimizer;
import com.vram_killer.client.leak.LeakDetector;
import com.vram_killer.client.render.TextureUploadInterceptor;
import com.vram_killer.client.scheduler.TextureScheduler;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

@Mixin(TextureAtlas.class)
public class SpriteAtlasTextureMixin {

	@Inject(method = "upload", at = @At("RETURN"))
	private void vram_killer$afterUpload(SpriteLoader.Preparations preparations, CallbackInfo ci) {
		try {
			VRAMKillerClient.ensureInitialized();

			TextureAtlas self = (TextureAtlas) (Object) this;
			GpuTexture gpuTexture = self.getTexture();

			if (gpuTexture == null) return;

			int atlasWidth = gpuTexture.getWidth(0);
			int atlasHeight = gpuTexture.getHeight(0);

			String path = "atlas:" + gpuTexture.getLabel();

			TextureScheduler scheduler = VRAMKillerClient.getTextureScheduler();
			if (scheduler != null) {
				scheduler.registerTexture(0, path, atlasWidth, atlasHeight,
					false, false, false, false, true);
			}

			VRAMManager vramManager = VRAMKillerClient.getVRAMManager();
			if (vramManager != null) {
				LeakDetector leakDetector = vramManager.getLeakDetector();
				if (leakDetector != null) {
					leakDetector.trackTextureCreation(0, atlasWidth, atlasHeight, path);
				}
			}

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
							0, contents, atlasWidth, atlasHeight);
					}
				}
			}

			convertAtlasFromSprites(self, gpuTexture, path, preparations);

		} catch (Exception e) {
			VRAMKiller.LOGGER.debug("Atlas tracking error: {}", e.getMessage());
		}
	}

	private void convertAtlasFromSprites(TextureAtlas atlas, GpuTexture gpuTexture, String path,
										  SpriteLoader.Preparations preparations) {
		TextureUploadInterceptor interceptor = VRAMKillerClient.getUploadInterceptor();
		if (interceptor == null || !interceptor.isEnabled()) return;

		if (!(gpuTexture instanceof GlTexture glTexture)) return;

		int glId = glTexture.glId();
		if (glId <= 0) return;

		int width = gpuTexture.getWidth(0);
		int height = gpuTexture.getHeight(0);

		if (width < 4 || height < 4) return;

		byte[] atlasPixels = assembleAtlasFromSprites(preparations, width, height, path);
		if (atlasPixels == null) return;

		boolean isBinaryAlpha = checkBinaryAlpha(atlasPixels);
		if (!isBinaryAlpha) {
			interceptor.getSkippedCount();
			return;
		}

		short[] rgb5a1Data = convertToRGB5A1(atlasPixels, width, height);

		ByteBuffer shortBuffer = ByteBuffer.allocateDirect(rgb5a1Data.length * 2)
			.order(ByteOrder.nativeOrder());

		for (short s : rgb5a1Data) {
			shortBuffer.putShort(s);
		}
		shortBuffer.flip();

		GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB5_A1,
			width, height, 0, GL11.GL_RGBA, GL12.GL_UNSIGNED_SHORT_5_5_5_1, shortBuffer);

		long originalSize = (long) width * height * 4L;
		long newSize = (long) width * height * 2L;

		float origKB = originalSize / 1024.0f;
		float newKB = newSize / 1024.0f;
		int count = interceptor.getConvertedCount();

		VRAMKiller.LOGGER.info("[VRAM-5551] ✅ ATLAS CONVERTED: {} {}x{} ({}KB→{}KB) [#{}]",
			path, width, height,
			String.format("%.1f", origKB), String.format("%.1f", newKB), count);
	}

	private byte[] assembleAtlasFromSprites(SpriteLoader.Preparations preparations, int atlasW, int atlasH, String atlasPath) {
		byte[] atlasPixels = new byte[atlasW * atlasH * 4];
		int assembledRegions = 0;
		int skippedRegions = 0;

		Map<?, ?> regions = preparations.regions();

		for (var entry : regions.entrySet()) {
			try {
				Object region = entry.getValue();
				SpriteContents contents = null;
				int spriteX = 0, spriteY = 0, spriteW = 0, spriteH = 0;

				java.lang.reflect.Field contentsField = null;
				for (java.lang.reflect.Field f : region.getClass().getDeclaredFields()) {
					if (f.getType() == SpriteContents.class) {
						contentsField = f;
						break;
					}
				}

				if (contentsField == null) continue;
				contentsField.setAccessible(true);
				contents = (SpriteContents) contentsField.get(region);

				if (contents == null) continue;

				spriteW = contents.width();
				spriteH = contents.height();

				java.lang.reflect.Field imageField = null;
				for (java.lang.reflect.Field f : contents.getClass().getDeclaredFields()) {
					if (f.getType() == NativeImage.class) {
						imageField = f;
						break;
					}
				}

				if (imageField == null) continue;
				imageField.setAccessible(true);
				NativeImage image = (NativeImage) imageField.get(contents);

				if (image == null || image.isClosed()) { skippedRegions++; continue; }

				int[] abgrPixels = image.getPixelsABGR();
				if (abgrPixels == null || abgrPixels.length == 0) { skippedRegions++; continue; }

				for (java.lang.reflect.Field posField : region.getClass().getDeclaredFields()) {
					if (posField.getType() == Integer.TYPE || posField.getType() == int.class) {
						posField.setAccessible(true);
						try {
							int val = posField.getInt(region);
							if (val >= 0 && val < Math.max(atlasW, atlasH)) {
								if (spriteX == 0 && val < atlasW && posField.getName().toLowerCase().contains("x")) spriteX = val;
								else if (spriteY == 0 && val < atlasH && posField.getName().toLowerCase().contains("y")) spriteY = val;
							}
						} catch (Exception ignored) {}
					}
				}

				copySpriteToAtlas(atlasPixels, abgrPixels, spriteX, spriteY, spriteW, spriteH, atlasW);
				assembledRegions++;

			} catch (Exception e) {
				skippedRegions++;
			}
		}

		if (assembledRegions == 0) {
			VRAMKiller.LOGGER.warn("[VRAM-ATLAS] Could not assemble {} from {} regions (skipped: {})",
				atlasPath, regions.size(), skippedRegions);
			return null;
		}

		VRAMKiller.LOGGER.info("[VRAM-ATLAS] Assembled {}: {} regions copied ({} skipped)", atlasPath, assembledRegions, skippedRegions);
		return atlasPixels;
	}

	private void copySpriteToAtlas(byte[] atlasPixels, int[] abgrPixels, int x, int y, int w, int h, int atlasW) {
		for (int sy = 0; sy < h; sy++) {
			for (int sx = 0; sx < w; sx++) {
				int abgr = abgrPixels[sy * w + sx];
				int atlasIdx = ((y + sy) * atlasW + (x + sx)) * 4;
				if (atlasIdx >= 0 && atlasIdx + 3 < atlasPixels.length) {
					atlasPixels[atlasIdx] = (byte) (abgr & 0xFF);
					atlasPixels[atlasIdx + 1] = (byte) ((abgr >> 8) & 0xFF);
					atlasPixels[atlasIdx + 2] = (byte) ((abgr >> 16) & 0xFF);
					atlasPixels[atlasIdx + 3] = (byte) ((abgr >> 24) & 0xFF);
				}
			}
		}
	}

	private boolean checkBinaryAlpha(byte[] rgbaPixels) {
		for (int i = 3; i < rgbaPixels.length; i += 4) {
			byte a = rgbaPixels[i];
			if (a != 0 && a != (byte) 255) return false;
		}
		return true;
	}

	private short[] convertToRGB5A1(byte[] rgbaPixels, int width, int height) {
		short[] output = new short[width * height];
		int pixelCount = width * height;
		for (int i = 0; i < pixelCount; i++) {
			int idx = i * 4;
			int r = (rgbaPixels[idx] & 0xFF) >> 3;
			int g = (rgbaPixels[idx + 1] & 0xFF) >> 3;
			int b = (rgbaPixels[idx + 2] & 0xFF) >> 3;
			int a = (rgbaPixels[idx + 3] & 0xFF) > 127 ? 1 : 0;
			output[i] = (short) ((r << 11) | (g << 6) | (b << 1) | a);
		}
		return output;
	}
}
