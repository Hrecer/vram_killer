package com.vram_killer.client.mixin.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import com.vram_killer.VRAMKiller;
import com.vram_killer.client.VRAMKillerClient;
import com.vram_killer.client.render.PixelDataCache;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpriteContents.class)
public abstract class SpriteContentsMixin {

	@Shadow
	private NativeImage originalImage;

	@Shadow
	public abstract int width();

	@Shadow
	public abstract int height();

	@Shadow
	public abstract net.minecraft.resources.Identifier name();

	private static int spriteUploadCount = 0;

	@Inject(method = "uploadFirstFrame", at = @At("RETURN"))
	private void onUploadFirstFrame(GpuTexture destination, int level, CallbackInfo ci) {
		try {
			if (level != 0) return;

			spriteUploadCount++;
			VRAMKillerClient.ensureInitialized();

			int w = width();
			int h = height();

			if (w < 4 || h < 4) return;

			if (originalImage == null || originalImage.isClosed()) return;

			int[] abgrPixels = originalImage.getPixelsABGR();
			if (abgrPixels == null || abgrPixels.length == 0) return;

			int firstPixel = abgrPixels[0];
			int centerIdx = (h / 2) * w + (w / 2);
			int centerPixel = abgrPixels[centerIdx];

			int firstR = firstPixel & 0xFF;
			int firstG = (firstPixel >> 8) & 0xFF;
			int firstB = (firstPixel >> 16) & 0xFF;
			int cr = centerPixel & 0xFF;
			int cg = (centerPixel >> 8) & 0xFF;
			int cb = (centerPixel >> 16) & 0xFF;

			boolean cornerZero = (firstR | firstG | firstB) == 0;
			boolean centerZero = (cr | cg | cb) == 0;

			if (cornerZero && centerZero) return;

			byte[] rgbaBytes = new byte[w * h * 4];
			int idx = 0;
			for (int abgr : abgrPixels) {
				rgbaBytes[idx++] = (byte) (abgr & 0xFF);
				rgbaBytes[idx++] = (byte) ((abgr >> 8) & 0xFF);
				rgbaBytes[idx++] = (byte) ((abgr >> 16) & 0xFF);
				rgbaBytes[idx++] = (byte) ((abgr >> 24) & 0xFF);
			}

			String spriteKey = "sprite:" + w + "x" + h + ":" + Integer.toHexString(firstPixel ^ centerPixel);
			PixelDataCache.put(spriteKey, rgbaBytes);

		} catch (Exception e) {
			if (spriteUploadCount <= 5) {
				VRAMKiller.LOGGER.warn("[VRAM-SPRITE] Error: {}: {}", e.getClass().getSimpleName(), e.getMessage());
			}
		}
	}
}
