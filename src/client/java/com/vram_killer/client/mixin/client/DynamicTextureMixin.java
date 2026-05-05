package com.vram_killer.client.mixin.client;

import com.mojang.blaze3d.textures.GpuTexture;
import com.vram_killer.VRAMKiller;
import com.vram_killer.client.VRAMKillerClient;
import com.vram_killer.client.leak.LeakDetector;
import com.vram_killer.client.scheduler.TextureScheduler;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DynamicTexture.class)
public class DynamicTextureMixin {

	@Unique
	private int vram_killer$capturedTextureId = 0;

	@Inject(method = "upload", at = @At("RETURN"))
	private void afterUpload(CallbackInfo ci) {
		trackDynamicTexture();
	}

	@Unique
	private void trackDynamicTexture() {
		try {
			DynamicTexture self = (DynamicTexture) (Object) this;
			GpuTexture gpuTexture = self.getTexture();

			if (gpuTexture == null) return;

			int width = gpuTexture.getWidth(0);
			int height = gpuTexture.getHeight(0);

			int glTextureId = vram_killer$getBoundTextureId();
			if (glTextureId <= 0) return;

			String path = "dynamic:" + gpuTexture.getLabel();

			TextureScheduler scheduler = VRAMKillerClient.getTextureScheduler();
			if (scheduler != null) {
				scheduler.registerTexture(glTextureId, path, width, height,
					false, false, false, false, true);
			}

			LeakDetector leakDetector = VRAMKillerClient.getVRAMManager().getLeakDetector();
			if (leakDetector != null) {
				leakDetector.trackTextureCreation(glTextureId, width, height, path);
			}

			VRAMKiller.LOGGER.debug("Dynamic texture uploaded: {} ({}x{}) id={}", gpuTexture.getLabel(), width, height, glTextureId);

		} catch (Exception e) {
			VRAMKiller.LOGGER.debug("Dynamic texture tracking error: {}", e.getMessage());
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
}
