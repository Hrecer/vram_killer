package com.vram_killer.client.mixin.client;

import com.vram_killer.VRAMKiller;
import com.vram_killer.client.VRAMKillerClient;
import com.vram_killer.client.monitor.VRAMMonitor;
import com.vram_killer.client.render.TextureUploadInterceptor;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftClientMixin {

	private static final int GL_QUERY_INTERVAL_FRAMES = 120;
	private static final int SCHEDULER_UPDATE_INTERVAL_FRAMES = 10;
	private static final int TEXTURE_ACCESS_CHECK_INTERVAL_FRAMES = 30;
	private static final int SHADOW_UPDATE_INTERVAL_FRAMES = 200;

	private int frameCounter = 0;
	private int schedulerFrameCounter = 0;
	private int textureAccessFrameCounter = 0;
	private int shadowFrameCounter = 0;
	private volatile int lastBoundTextureId = 0;
	private boolean gpuDetected = false;

	@Inject(method = "runTick", at = @At("HEAD"))
	private void onRunTick(boolean advanceGameTime, CallbackInfo ci) {
		try {
			if (!VRAMKillerClient.isInitialized()) {
				VRAMKillerClient.ensureInitialized();
				return;
			}

			if (!gpuDetected) {
				var monitor = VRAMKillerClient.getVRAMManager().getVRAMMonitor();
				if (monitor != null) {
					monitor.detectVRAMInfoFromRenderThread();
					gpuDetected = true;
				}
			}

			frameCounter++;
			schedulerFrameCounter++;
			textureAccessFrameCounter++;
			shadowFrameCounter++;

			if (schedulerFrameCounter >= SCHEDULER_UPDATE_INTERVAL_FRAMES) {
				schedulerFrameCounter = 0;

				var scheduler = VRAMKillerClient.getTextureScheduler();
				if (scheduler != null) {
					scheduler.processPendingGLDeletions();
				}

				var leakDetector = VRAMKillerClient.getVRAMManager().getLeakDetector();
				if (leakDetector != null) {
					leakDetector.processPendingGLDeletions();
				}

				TextureUploadInterceptor interceptor = VRAMKillerClient.getUploadInterceptor();
				if (interceptor != null) {
					interceptor.processPendingUploads();
				}
			}

			if (frameCounter >= GL_QUERY_INTERVAL_FRAMES) {
				frameCounter = 0;

				var monitor = VRAMKillerClient.getVRAMManager().getVRAMMonitor();
				if (monitor != null) {
					monitor.updateAvailableVRAMFromRenderThread();
				}
			}

			if (textureAccessFrameCounter >= TEXTURE_ACCESS_CHECK_INTERVAL_FRAMES) {
				textureAccessFrameCounter = 0;

				var scheduler = VRAMKillerClient.getTextureScheduler();
				if (scheduler != null) {
					int currentTexture = getCurrentlyBoundTextureCached();
					if (currentTexture != 0 && currentTexture != lastBoundTextureId) {
						scheduler.markTextureAccessed(currentTexture);
						lastBoundTextureId = currentTexture;
					}
				}
			}

			if (shadowFrameCounter >= SHADOW_UPDATE_INTERVAL_FRAMES) {
				shadowFrameCounter = 0;

				var shaderOptimizer = VRAMKillerClient.getShaderOptimizer();
				if (shaderOptimizer != null) {
					shaderOptimizer.updateDynamicShadows();
				}
			}

		} catch (Exception e) {
			VRAMKiller.LOGGER.debug("Render hook error: {}", e.getMessage());
		}
	}

	@Inject(method = "stop", at = @At("HEAD"))
	private void onStop(CallbackInfo ci) {
		try {
			if (!VRAMKillerClient.isInitialized()) return;

			var scheduler = VRAMKillerClient.getTextureScheduler();
			if (scheduler != null) {
				scheduler.processPendingGLDeletions();
				scheduler.shutdown();
			}

			var leakDetector = VRAMKillerClient.getVRAMManager().getLeakDetector();
			if (leakDetector != null) {
				leakDetector.processPendingGLDeletions();
			}

			TextureUploadInterceptor interceptor = VRAMKillerClient.getUploadInterceptor();
			if (interceptor != null) {
				interceptor.shutdown();
			}

			var shaderOptimizer = VRAMKillerClient.getShaderOptimizer();
			if (shaderOptimizer != null) {
				shaderOptimizer.shutdown();
			}

			var cacheManager = VRAMKillerClient.getVRAMManager().getCacheManager();
			if (cacheManager != null) {
				cacheManager.shutdown();
			}

		} catch (Exception e) {
			VRAMKiller.LOGGER.error("Shutdown error: {}", e.getMessage());
		}
	}

	private int getCurrentlyBoundTextureCached() {
		try {
			int[] textureId = {0};
			org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D, textureId);
			return textureId[0];
		} catch (Exception e) {
			return 0;
		}
	}
}
