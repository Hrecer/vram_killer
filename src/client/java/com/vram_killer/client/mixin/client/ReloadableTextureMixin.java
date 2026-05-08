package com.vram_killer.client.mixin.client;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.vram_killer.VRAMKiller;
import com.vram_killer.client.VRAMKillerClient;
import com.vram_killer.client.render.TextureUploadInterceptor;
import com.vram_killer.client.render.PixelDataCache;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ReloadableTexture.class)
public abstract class ReloadableTextureMixin {

	private static int callCount = 0;

	@Shadow
	@Final
	private net.minecraft.resources.Identifier resourceId;

	@Inject(method = "doLoad", at = @At("RETURN"))
	private void afterDoLoad(NativeImage image, CallbackInfo ci) {
		try {
			callCount++;
			if (callCount <= 5) {
				VRAMKiller.LOGGER.info("[VRAM-MIXIN] doLoad #{} RETURN for: {}",
					callCount, this.resourceId);
			} else if (callCount == 6) {
				VRAMKiller.LOGGER.info("[VRAM-MIXIN] ... (suppressing further per-texture logs)");
			}

			VRAMKillerClient.ensureInitialized();

			if ((Object) this instanceof DynamicTexture) return;

			TextureUploadInterceptor interceptor = VRAMKillerClient.getUploadInterceptor();
			if (interceptor == null || !interceptor.isEnabled()) return;

			int width = image.getWidth();
			int height = image.getHeight();
			if (width < 16 || height < 16) return;
			if ((width & 3) != 0 || (height & 3) != 0) return;

			String path = this.resourceId.toString();
			byte[] cachedPixels = PixelDataCache.get(path);

			GpuTexture gpuTexture = getTextureField();
			if (gpuTexture == null) return;
			if (!(gpuTexture instanceof GlTexture glTexture)) return;

			int glId = getGlTextureId(glTexture);
			if (glId <= 0) return;

			if (cachedPixels != null) {
				if (callCount <= 10) VRAMKiller.LOGGER.info("[VRAM-MIXIN] Using CACHED pixels for: {}", path);
				boolean success = interceptor.convertToRGB5A1(glId, width, height, path, cachedPixels);
				if (!success && callCount <= 5) {
					VRAMKiller.LOGGER.info("[VRAM-MIXIN] RGB5A1 conversion skipped for {}", path);
				}
			} else {
				if (callCount <= 5) VRAMKiller.LOGGER.info("[VRAM-MIXIN] No cache for: {}, skipping", path);
			}

		} catch (IllegalStateException e) {
			VRAMKiller.LOGGER.debug("[VRAM-MIXIN] Post-load error (ISE): {}", e.getMessage());
		} catch (Exception e) {
			if (callCount <= 5) {
				VRAMKiller.LOGGER.info("[VRAM-MIXIN] Post-load ERROR: {}: {}",
					e.getClass().getSimpleName(), e.getMessage());
				e.printStackTrace();
			}
		}
	}

	private GpuTexture getTextureField() {
		try {
			Class<?> clazz = ((Object) this).getClass();
			while (clazz != null) {
				try {
					java.lang.reflect.Field f = clazz.getDeclaredField("texture");
					f.setAccessible(true);
					return (GpuTexture) f.get(this);
				} catch (NoSuchFieldException ignored) {}
				clazz = clazz.getSuperclass();
			}
			return null;
		} catch (Exception e) {
			return null;
		}
	}

	private int getGlTextureId(GlTexture glTexture) {
		try {
			java.lang.reflect.Field f = GlTexture.class.getDeclaredField("id");
			f.setAccessible(true);
			return f.getInt(glTexture);
		} catch (Exception e) {
			return 0;
		}
	}
}
