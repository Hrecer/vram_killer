package com.vram_killer.client.mixin.client;

import com.mojang.blaze3d.textures.GpuTexture;
import com.vram_killer.VRAMKiller;
import com.vram_killer.client.VRAMKillerClient;
import com.vram_killer.client.leak.LeakDetector;
import com.vram_killer.client.render.TextureUploadInterceptor;
import com.vram_killer.client.scheduler.TextureScheduler;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureManager.class)
public class TextureManagerMixin {

	@Inject(method = "release", at = @At("HEAD"))
	private void onDestroyTexture(Identifier location, CallbackInfo ci) {
		try {
			if (location == null) return;

			VRAMKiller.LOGGER.debug("Texture destroyed: {}", location);

		} catch (Exception e) {
			VRAMKiller.LOGGER.debug("Texture destroy tracking error: {}", e.getMessage());
		}
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void onTick(CallbackInfo ci) {
		try {
			TextureUploadInterceptor interceptor = VRAMKillerClient.getUploadInterceptor();
			if (interceptor != null) {
				interceptor.processPendingUploads();
			}

			TextureScheduler scheduler = VRAMKillerClient.getTextureScheduler();
			if (scheduler != null) {
				scheduler.processPendingGLDeletions();
			}

			LeakDetector leakDetector = VRAMKillerClient.getVRAMManager().getLeakDetector();
			if (leakDetector != null) {
				leakDetector.processPendingGLDeletions();
			}

		} catch (Exception e) {
			VRAMKiller.LOGGER.debug("Texture manager tick error: {}", e.getMessage());
		}
	}
}
