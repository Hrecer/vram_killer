package com.vram_killer.client.mixin.client;

import com.vram_killer.VRAMKiller;
import com.vram_killer.client.VRAMKillerClient;
import com.vram_killer.client.leak.LeakDetector;
import com.vram_killer.client.scheduler.TextureScheduler;
import com.mojang.blaze3d.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlStateManager.class)
public class GlStateManagerMixin {

	@Unique
	private static int vram_killer$bindCounter = 0;

	@Unique
	private static final int BIND_TRACK_INTERVAL = 3;

	@Inject(method = "_deleteTexture", at = @At("HEAD"))
	private static void onDeleteTexture(int textureId, CallbackInfo ci) {
		try {
			if (textureId <= 0) return;

			LeakDetector leakDetector =
				VRAMKillerClient.getVRAMManager().getLeakDetector();

			if (leakDetector != null) {
				leakDetector.trackTextureDestruction(textureId);
			}

		} catch (Exception e) {
			VRAMKiller.LOGGER.debug("Delete texture tracking error: {}", e.getMessage());
		}
	}

	@Inject(method = "_bindTexture", at = @At("HEAD"))
	private static void onBindTexture(int textureId, CallbackInfo ci) {
		try {
			if (textureId <= 0) return;

			vram_killer$bindCounter++;
			if (vram_killer$bindCounter % BIND_TRACK_INTERVAL != 0) return;

			var scheduler = VRAMKillerClient.getTextureScheduler();
			if (scheduler != null) {
				scheduler.markTextureAccessed(textureId);
			}

			LeakDetector leakDetector = VRAMKillerClient.getVRAMManager().getLeakDetector();
			if (leakDetector != null) {
				leakDetector.markTextureAccessed(textureId);
			}

		} catch (Exception e) {
			VRAMKiller.LOGGER.debug("Bind texture tracking error: {}", e.getMessage());
		}
	}
}
