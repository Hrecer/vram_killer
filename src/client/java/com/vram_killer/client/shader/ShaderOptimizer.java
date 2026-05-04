package com.vram_killer.client.shader;

import com.vram_killer.VRAMKiller;
import com.vram_killer.client.VRAMKillerClient;
import com.vram_killer.config.VRAMKillerConfigBase;
import org.lwjgl.opengl.GL11;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ShaderOptimizer {
	private static final int DEFAULT_SHADOW_RESOLUTION = 2048;

	private final ConcurrentHashMap<Integer, FramebufferInfo> trackedFramebuffers;
	private final AtomicInteger currentShadowResolution;
	private final AtomicLong totalFramebufferMemory;
	private volatile boolean optimizationEnabled;
	private boolean irisDetected;

	public ShaderOptimizer() {
		this.trackedFramebuffers = new ConcurrentHashMap<>();
		this.currentShadowResolution = new AtomicInteger(DEFAULT_SHADOW_RESOLUTION);
		this.totalFramebufferMemory = new AtomicLong(0);
		this.optimizationEnabled = VRAMKillerConfigBase.Compression.enabled;

		detectShaderMods();
	}

	public void initialize() {
		if (!optimizationEnabled) {
			VRAMKiller.LOGGER.info("Shader optimizer disabled in configuration");
			return;
		}

		VRAMKiller.LOGGER.info("Shader optimizer initialized - Iris: {} | Dynamic shadows: {}",
			irisDetected, VRAMKillerConfigBase.Shadow.dynamicResolution);
	}

	public void trackFramebufferCreation(int framebufferId, int[] colorAttachments,
										int depthAttachment,
										int width, int height, String source) {
		if (!optimizationEnabled || framebufferId <= 0) return;

		long estimatedSize = estimateFramebufferSize(width, height, colorAttachments.length + (depthAttachment > 0 ? 1 : 0));

		FramebufferInfo info = new FramebufferInfo(
			framebufferId, width, height, colorAttachments, depthAttachment,
			source, estimatedSize, System.currentTimeMillis()
		);

		trackedFramebuffers.put(framebufferId, info);
		totalFramebufferMemory.addAndGet(estimatedSize);

		VRAMKiller.LOGGER.debug("Framebuffer tracked: {}x{} ({} MB) from {} [id={}, color={}, depth={}]",
			width, height, estimatedSize / (1024 * 1024), source,
			framebufferId, colorAttachments.length, depthAttachment);
	}

	public void trackFramebufferDeletion(int framebufferId) {
		FramebufferInfo removed = trackedFramebuffers.remove(framebufferId);
		if (removed != null) {
			totalFramebufferMemory.addAndGet(-removed.estimatedSize);
		}
	}

	public void updateDynamicShadows() {
		if (!VRAMKillerConfigBase.Shadow.dynamicResolution || !irisDetected) return;

		double vramUsage = VRAMKillerClient.getVRAMManager().getVRAMUsagePercent();

		int targetResolution;

		if (vramUsage > 90) {
			targetResolution = VRAMKillerConfigBase.Shadow.resolutionMin;
		} else if (vramUsage > 75) {
			targetResolution = VRAMKillerConfigBase.Shadow.resolutionMin +
				(VRAMKillerConfigBase.Shadow.resolutionMax - VRAMKillerConfigBase.Shadow.resolutionMin) / 3;
		} else if (vramUsage > 60) {
			targetResolution = (VRAMKillerConfigBase.Shadow.resolutionMin + VRAMKillerConfigBase.Shadow.resolutionMax) / 2;
		} else {
			targetResolution = VRAMKillerConfigBase.Shadow.resolutionMax;
		}

		int currentRes = currentShadowResolution.get();
		if (currentRes != targetResolution) {
			currentShadowResolution.set(targetResolution);

			VRAMKiller.LOGGER.debug("Dynamic shadow resolution adjusted: {} -> {} (VRAM: " + String.format("%.1f%%", vramUsage) + ")",
				currentRes, targetResolution);
		}
	}

	public int getRecommendedShadowResolution() {
		return currentShadowResolution.get();
	}

	public long getTotalFramebufferMemory() {
		return totalFramebufferMemory.get();
	}

	public int getTrackedFramebufferCount() {
		return trackedFramebuffers.size();
	}

	public boolean isIrisDetected() {
		return irisDetected;
	}

	private long estimateFramebufferSize(int width, int height, int attachmentCount) {
		long perAttachment = width * height * 4L;
		return perAttachment * attachmentCount + 256;
	}

	private void detectShaderMods() {
		try {
			Class.forName("net.coderbot.iris.Iris");
			irisDetected = true;
			VRAMKiller.LOGGER.info("Iris shader mod detected (coderbot) - Shadow optimizations enabled");
		} catch (ClassNotFoundException ignored) {}

		if (!irisDetected) {
			try {
				Class.forName("net.irisshaders.iris.Iris");
				irisDetected = true;
				VRAMKiller.LOGGER.info("Iris shader mod detected (irisshaders) - Shadow optimizations enabled");
			} catch (ClassNotFoundException ignored) {}
		}

		try {
			Class.forName("org.vivecraft.VRSettings");
			VRAMKiller.LOGGER.info("Vivecraft detected - Adjusting stereo rendering optimizations");
		} catch (ClassNotFoundException ignored) {}
	}

	public void shutdown() {
		trackedFramebuffers.clear();
		totalFramebufferMemory.set(0);

		VRAMKiller.LOGGER.info("Shader optimizer shut down");
	}

	public static class FramebufferInfo {
		final int framebufferId;
		final int width;
		final int height;
		final int[] colorAttachments;
		final int depthAttachment;
		final String source;
		final long estimatedSize;
		final long createdTime;
		volatile boolean downgraded;

		public FramebufferInfo(int framebufferId, int width, int height, int[] colorAttachments,
							 int depthAttachment, String source, long estimatedSize, long createdTime) {
			this.framebufferId = framebufferId;
			this.width = width;
			this.height = height;
			this.colorAttachments = colorAttachments;
			this.depthAttachment = depthAttachment;
			this.source = source;
			this.estimatedSize = estimatedSize;
			this.createdTime = createdTime;
			this.downgraded = false;
		}
	}
}
