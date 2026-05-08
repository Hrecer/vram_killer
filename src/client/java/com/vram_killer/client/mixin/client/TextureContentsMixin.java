package com.vram_killer.client.mixin.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.vram_killer.VRAMKiller;
import com.vram_killer.client.render.PixelDataCache;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TextureContents.class)
public class TextureContentsMixin {

	private static int cacheHits = 0;

	@Inject(method = "load", at = @At("RETURN"), cancellable = false)
	private static void afterLoad(
		net.minecraft.server.packs.resources.ResourceManager resourceManager,
		Identifier location,
		CallbackInfoReturnable<TextureContents> cir
	) {
		try {
			TextureContents contents = cir.getReturnValue();
			if (contents == null) return;

			NativeImage image = contents.image();
			if (image == null) return;

			int w = image.getWidth();
			int h = image.getHeight();

			if (w < 16 || h < 16) return;
			if ((w & 3) != 0 || (h & 3) != 0) return;

			String key = location.toString();

			int[] abgrPixels = image.getPixelsABGR();
			if (abgrPixels == null || abgrPixels.length == 0) return;

			if (cacheHits <= 5) {
				VRAMKiller.LOGGER.info("[VRAM-CACHE] Inspecting: {} {}x{} format={} closed={}",
					key, w, h, image.format(), image.isClosed());
			}

			int sampleStep = Math.max(1, abgrPixels.length / 512);
			int nonZeroRGBCount = 0;
			int totalSampled = 0;

			int[] cornerSamples = new int[4];
			cornerSamples[0] = abgrPixels[0];
			cornerSamples[1] = abgrPixels[w - 1];
			cornerSamples[2] = abgrPixels[(h - 1) * w];
			cornerSamples[3] = abgrPixels[h * w - 1];

			int centerIdx = (h / 2) * w + (w / 2);
			int centerPixel = abgrPixels[centerIdx];

			for (int i = 0; i < abgrPixels.length; i += sampleStep) {
				int p = abgrPixels[i];
				int pr = p & 0xFF, pg = (p >> 8) & 0xFF, pb = (p >> 16) & 0xFF;
				if ((pr | pg | pb) != 0) nonZeroRGBCount++;
				totalSampled++;
			}
			double nonZeroRGBRatio = totalSampled > 0 ? (double) nonZeroRGBCount / totalSampled : 0;

			if (nonZeroRGBRatio < 0.02) {
				if (cacheHits <= 5) VRAMKiller.LOGGER.warn("[VRAM-CACHE] SKIP: {} has very low RGB ratio {:.2%} (center=0x{})",
					key, nonZeroRGBRatio, Integer.toHexString(centerPixel));
				return;
			}

			byte[] rgbaBytes = new byte[w * h * 4];
		int idx = 0;
		for (int abgr : abgrPixels) {
			rgbaBytes[idx++] = (byte) (abgr & 0xFF);
			rgbaBytes[idx++] = (byte) ((abgr >> 8) & 0xFF);
			rgbaBytes[idx++] = (byte) ((abgr >> 16) & 0xFF);
			rgbaBytes[idx++] = (byte) ((abgr >> 24) & 0xFF);
		}

			PixelDataCache.put(key, rgbaBytes);
			cacheHits++;

			if (cacheHits <= 5) {
				int s = abgrPixels[0];
				VRAMKiller.LOGGER.info("[VRAM-CACHE] Cached: {} {}x{} R={} G={} B={} A={}",
					key, w, h,
					(s & 0xFF), ((s >> 8) & 0xFF), ((s >> 16) & 0xFF), ((s >> 24) & 0xFF));
			} else if (cacheHits == 6) {
				VRAMKiller.LOGGER.info("[VRAM-CACHE] ... (cached {} textures)", cacheHits);
			}

		} catch (Exception e) {
			VRAMKiller.LOGGER.debug("[VRAM-CACHE] Error: {}", e.getMessage());
		}
	}
}
