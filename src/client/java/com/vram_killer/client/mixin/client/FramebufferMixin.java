package com.vram_killer.client.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.vram_killer.VRAMKiller;
import com.vram_killer.client.VRAMKillerClient;
import com.vram_killer.client.shader.ShaderOptimizer;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicInteger;

@Mixin(RenderTarget.class)
public class FramebufferMixin {
	
	private static final AtomicInteger framebufferCounter = new AtomicInteger(0);
	
	@Shadow
	public int width;
	
	@Shadow
	public int height;
	
	@Shadow
	@Final
	protected boolean useDepth;
	
	@Shadow
	@Final
	protected String label;
	
	@Shadow
	@org.jspecify.annotations.Nullable
	protected GpuTexture colorTexture;
	
	@Unique
	private int vram_killer$framebufferId = 0;
	@Unique
	private long vram_killer$estimatedMemory = 0;
	
	@Inject(method = "createBuffers", at = @At("RETURN"))
	private void afterCreateBuffers(int width, int height, CallbackInfo ci) {
		try {
			if (width <= 0 || height <= 0) return;
			
			vram_killer$framebufferId = framebufferCounter.incrementAndGet();
			
			long colorSize = 0L;
			if (colorTexture != null) {
				colorSize = (long) width * height * 4L;
				int mipLevels = colorTexture.getMipLevels();
				for (int i = 1; i < mipLevels; i++) {
					colorSize += ((long) (width >> i) * (height >> i)) * 4L;
				}
			}
			
			long depthSize = useDepth ? (long) width * height * 4L : 0L;
			vram_killer$estimatedMemory = colorSize + depthSize;
			
			String source = detectSource();
			
			ShaderOptimizer shaderOptimizer = VRAMKillerClient.getShaderOptimizer();
			if (shaderOptimizer != null) {
				shaderOptimizer.trackFramebufferCreation(
					vram_killer$framebufferId,
					new int[0],
					0,
					width,
					height,
					source
				);
				
				VRAMKiller.LOGGER.debug("Framebuffer: {}x{} ({} MB) from {}", 
					width, height, vram_killer$estimatedMemory / (1024 * 1024), source);
			}
			
		} catch (Exception e) {
			VRAMKiller.LOGGER.debug("Framebuffer tracking error: {}", e.getMessage());
		}
	}
	
	@Inject(method = "destroyBuffers", at = @At("HEAD"))
	private void beforeDestroyBuffers(CallbackInfo ci) {
		try {
			if (vram_killer$framebufferId <= 0) return;
			
			ShaderOptimizer shaderOptimizer = VRAMKillerClient.getShaderOptimizer();
			if (shaderOptimizer != null) {
				shaderOptimizer.trackFramebufferDeletion(vram_killer$framebufferId);
			}
			
		} catch (Exception e) {
			VRAMKiller.LOGGER.debug("Framebuffer delete tracking error: {}", e.getMessage());
		}
	}
	
	@Unique
	private String detectSource() {
		String labelStr = label != null ? label.toLowerCase() : "";
		
		if (labelStr.contains("iris") || labelStr.contains("shadow") || 
			labelStr.contains("composite") || labelStr.contains("deferred")) {
			return "iris:" + label;
		}
		if (labelStr.contains("sodium")) {
			return "sodium:" + label;
		}
		if (labelStr.contains("oculus")) {
			return "oculus:" + label;
		}
		
		try {
			StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
			
			for (int i = 2; i < Math.min(stackTrace.length, 20); i++) {
				String className = stackTrace[i].getClassName();
				
				if (className.contains("iris") || className.contains("Iris")) {
					return "iris:" + stackTrace[i].getMethodName();
				}
				if (className.contains("sodium") || className.contains("Sodium")) {
					return "sodium:" + stackTrace[i].getMethodName();
				}
				if (className.contains("oculus") || className.contains("Oculus")) {
					return "oculus:" + stackTrace[i].getMethodName();
				}
			}
		} catch (Exception ignored) {}
		
		return "minecraft:" + (label != null ? label : "unknown");
	}
}
