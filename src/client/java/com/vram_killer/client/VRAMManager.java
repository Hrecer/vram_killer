package com.vram_killer.client;

import com.vram_killer.VRAMKiller;
import com.vram_killer.client.cache.TextureCacheManager;
import com.vram_killer.client.compatibility.SodiumIntegrationBridge;
import com.vram_killer.client.leak.LeakDetector;
import com.vram_killer.client.monitor.VRAMMonitor;
import com.vram_killer.client.scheduler.TextureScheduler;
import com.vram_killer.config.VRAMKillerConfigBase;

public class VRAMManager {
	private final TextureCacheManager cacheManager;
	private final LeakDetector leakDetector;
	private final VRAMMonitor vramMonitor;

	private volatile boolean initialized = false;

	public VRAMManager() {
		this.cacheManager = new TextureCacheManager();
		this.leakDetector = new LeakDetector();
		this.vramMonitor = new VRAMMonitor();
	}

	public void initialize() {
		if (initialized) return;

		cacheManager.initialize();
		leakDetector.initialize();
		vramMonitor.start();
		SodiumIntegrationBridge.detectAndInitialize();

		initialized = true;
		VRAMKiller.LOGGER.info("VRAM Manager fully initialized with all subsystems online");
	}

	public TextureCacheManager getCacheManager() {
		return cacheManager;
	}

	public LeakDetector getLeakDetector() {
		return leakDetector;
	}

	public VRAMMonitor getVRAMMonitor() {
		return vramMonitor;
	}

	public TextureScheduler getTextureScheduler() {
		return VRAMKillerClient.getTextureScheduler();
	}

	public long getTotalTextureMemoryUsage() {
		return cacheManager.getEstimatedTotalSize();
	}

	public double getVRAMUsagePercent() {
		return vramMonitor.getCurrentVRAMUsagePercent();
	}

	public boolean isNearCapacity() {
		return getVRAMUsagePercent() > VRAMKillerConfigBase.Scheduler.maxVRAMUsagePercent;
	}
}
