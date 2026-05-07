package com.vram_killer.client.mixin.client;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.vram_killer.VRAMKiller;
import com.vram_killer.client.VRAMKillerClient;
import com.vram_killer.client.render.TextureUploadInterceptor;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ReloadableTexture.class)
public abstract class ReloadableTextureMixin {

	@Shadow
	@Final
	private net.minecraft.resources.Identifier resourceId;

	@Inject(method = "doLoad", at = @At("HEAD"), cancellable = true)
	private void onDoLoad(NativeImage image, CallbackInfo ci) {
		try {
			if (!VRAMKillerClient.isInitialized()) return;

			TextureUploadInterceptor interceptor = VRAMKillerClient.getUploadInterceptor();
			if (interceptor == null || !interceptor.isEnabled()) return;

			GpuDevice device = RenderSystem.getDevice();
			GpuTexture gpuTexture = device.createTexture(
				this.resourceId::toString,
				5,
				com.mojang.blaze3d.textures.TextureFormat.RGBA8,
				image.getWidth(),
				image.getHeight(),
				1, 1
			);

			if (gpuTexture instanceof GlTexture glTexture) {
				int glTextureId = getGlTextureId(glTexture);

				if (glTextureId > 0 && interceptor.tryInterceptUpload(image, this.resourceId.toString(), glTextureId)) {
					((ReloadableTexture) (Object) this).close();

					try {
						java.lang.reflect.Field textureField = net.minecraft.client.renderer.texture.AbstractTexture.class.getDeclaredField("texture");
						textureField.setAccessible(true);
						textureField.set(this, gpuTexture);

						java.lang.reflect.Field textureViewField = net.minecraft.client.renderer.texture.AbstractTexture.class.getDeclaredField("textureView");
						textureViewField.setAccessible(true);
						textureViewField.set(this, device.createTextureView(gpuTexture));
					} catch (Exception e) {
						VRAMKiller.LOGGER.debug("Failed to set texture fields: {}", e.getMessage());
					}

					ci.cancel();
					return;
				}
			}

			gpuTexture.close();

		} catch (Exception e) {
			VRAMKiller.LOGGER.debug("ReloadableTexture mixin error: {}", e.getMessage());
		}
	}

	private int getGlTextureId(GlTexture glTexture) {
		try {
			java.lang.reflect.Field idField = GlTexture.class.getDeclaredField("id");
			idField.setAccessible(true);
			return idField.getInt(glTexture);
		} catch (Exception e) {
			VRAMKiller.LOGGER.debug("Failed to get GL texture id: {}", e.getMessage());
			return 0;
		}
	}
}
