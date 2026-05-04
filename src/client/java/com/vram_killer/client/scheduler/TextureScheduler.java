package com.vram_killer.client.scheduler;

import com.vram_killer.VRAMKiller;
import com.vram_killer.client.VRAMKillerClient;
import com.vram_killer.client.monitor.VRAMMonitor;
import com.vram_killer.config.VRAMKillerConfigBase;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class TextureScheduler {
	private static final long EVICTION_CHECK_INTERVAL_MS = 5000;
	private static final long GRACE_PERIOD_MS = VRAMKillerConfigBase.Scheduler.coldZoneDelaySeconds * 1000L;
	private static final long MIN_IDLE_FOR_EVICT_MS = 300_000L;
	private static final int BYTES_PER_PIXEL_RGBA8 = 4;
	private static final int BYTES_PER_PIXEL_BC1 = 1;
	private static final int BYTES_PER_PIXEL_BC3 = 1;
	private static final int BYTES_PER_PIXEL_BC5 = 1;
	private static final int BYTES_PER_PIXEL_BC7 = 1;

	private final ConcurrentHashMap<Integer, TextureEntry> textureRegistry;
	private final ConcurrentHashMap<Integer, Long> hotZoneAccessMap;
	private final Set<Integer> warmZoneSet;
	private final Set<Integer> coldZoneSet;
	private final ConcurrentLinkedQueue<Integer> pendingGLDeletions;
	private final ScheduledExecutorService scheduler;
	private final AtomicLong totalTextureMemory;
	private final AtomicLong evictedCount;

	private volatile boolean running;

	public TextureScheduler() {
		this.textureRegistry = new ConcurrentHashMap<>();
		this.hotZoneAccessMap = new ConcurrentHashMap<>();
		this.warmZoneSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
		this.coldZoneSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
		this.pendingGLDeletions = new ConcurrentLinkedQueue<>();
		this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "VRAM-Scheduler");
			t.setDaemon(true);
			return t;
		});
		this.totalTextureMemory = new AtomicLong(0);
		this.evictedCount = new AtomicLong(0);
		this.running = false;
	}

	public void initialize() {
		if (!VRAMKillerConfigBase.Scheduler.enabled) {
			VRAMKiller.LOGGER.info("Texture scheduler disabled in configuration");
			return;
		}

		scheduler.scheduleAtFixedRate(this::performEvictionCheck,
			EVICTION_CHECK_INTERVAL_MS, EVICTION_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

		scheduler.scheduleAtFixedRate(this::performWarmToColdTransition,
			30_000L, 30_000L, TimeUnit.MILLISECONDS);

		running = true;
		VRAMKiller.LOGGER.info("Texture scheduler initialized - Grace period: {}ms", GRACE_PERIOD_MS);
	}

	public void registerTexture(int textureId, String path, int width, int height,
							   boolean isGUI, boolean isFont, boolean isParticle,
							   boolean isNormalMap, boolean hasTransparency) {
		registerTexture(textureId, path, width, height, isGUI, isFont, isParticle, 
			isNormalMap, hasTransparency, false, VRAMKillerConfigBase.Compression.backgroundFormat);
	}

	public void registerTexture(int textureId, String path, int width, int height,
							   boolean isGUI, boolean isFont, boolean isParticle,
							   boolean isNormalMap, boolean hasTransparency, boolean isCompressed,
							   VRAMKillerConfigBase.Compression.CompressionFormat format) {
		TextureZone zone = determineInitialZone(path, isGUI, isFont, isParticle);

		TextureEntry entry = new TextureEntry(
			textureId, path, width, height, zone,
			isNormalMap, hasTransparency, System.currentTimeMillis(),
			isCompressed, format
		);

		textureRegistry.put(textureId, entry);

		switch (zone) {
			case HOT -> hotZoneAccessMap.put(textureId, System.currentTimeMillis());
			case WARM -> warmZoneSet.add(textureId);
			default -> coldZoneSet.add(textureId);
		}

		long estimatedMemory = estimateTextureMemory(width, height, isCompressed, format);
		totalTextureMemory.addAndGet(estimatedMemory);
	}

	public void markTextureAccessed(int textureId) {
		if (textureId == 0 || !running) return;

		TextureEntry entry = textureRegistry.get(textureId);
		if (entry == null) return;

		entry.lastAccessTime = System.currentTimeMillis();

		if (entry.zone != TextureZone.HOT) {
			moveToHotZone(textureId, entry);
		} else {
			hotZoneAccessMap.put(textureId, System.currentTimeMillis());
		}
	}

	public void processPendingGLDeletions() {
		Integer textureId;
		while ((textureId = pendingGLDeletions.poll()) != null) {
			try {
				if (isTextureStillBound(textureId)) {
					pendingGLDeletions.add(textureId);
					continue;
				}
				org.lwjgl.opengl.GL11.glDeleteTextures(textureId);
				VRAMKiller.LOGGER.debug("Deleted evicted texture {} on render thread", textureId);
			} catch (Exception e) {
				VRAMKiller.LOGGER.debug("Failed to delete texture {}: {}", textureId, e.getMessage());
			}
		}
	}

	public int getHotZoneSize() { return hotZoneAccessMap.size(); }
	public int getColdZoneSize() { return coldZoneSet.size(); }
	public int getPendingDeletionCount() { return pendingGLDeletions.size(); }
	public long getTotalEstimatedMemory() { return totalTextureMemory.get(); }
	public long getEvictedCount() { return evictedCount.get(); }

	private TextureZone determineInitialZone(String path, boolean isGUI, boolean isFont, boolean isParticle) {
		if (isGUI || isFont) return TextureZone.HOT;
		if (isParticle) return TextureZone.WARM;
		return TextureZone.COLD;
	}

	private long estimateTextureMemory(int width, int height) {
		return estimateTextureMemory(width, height, false, null);
	}

	private long estimateTextureMemory(int width, int height, boolean isCompressed, 
									   VRAMKillerConfigBase.Compression.CompressionFormat format) {
		if (isCompressed && format != null) {
			int bytesPerPixel = switch (format) {
				case BC1 -> BYTES_PER_PIXEL_BC1;
				case BC3 -> BYTES_PER_PIXEL_BC3;
				case BC5 -> BYTES_PER_PIXEL_BC5;
				case BC7 -> BYTES_PER_PIXEL_BC7;
			};
			return (long) width * height * bytesPerPixel;
		}
		return (long) width * height * BYTES_PER_PIXEL_RGBA8;
	}

	private boolean isTextureStillBound(int textureId) {
		try {
			int[] boundId = {0};
			org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D, boundId);
			return boundId[0] == textureId;
		} catch (Exception e) {
			return false;
		}
	}

	private void moveToHotZone(int textureId, TextureEntry entry) {
		coldZoneSet.remove(textureId);
		warmZoneSet.remove(textureId);
		hotZoneAccessMap.put(textureId, System.currentTimeMillis());
		entry.zone = TextureZone.HOT;
	}

	private void performEvictionCheck() {
		if (!running) return;

		VRAMMonitor monitor;
		try {
			monitor = VRAMKillerClient.getVRAMManager().getVRAMMonitor();
		} catch (Exception e) {
			return;
		}
		if (monitor == null || !monitor.isVRAMCritical()) return;

		long now = System.currentTimeMillis();

		List<Integer> toEvict = new ArrayList<>();
		for (Map.Entry<Integer, Long> mapEntry : hotZoneAccessMap.entrySet()) {
			if (!monitor.isVRAMCritical()) break;
			long lastAccess = mapEntry.getValue();
			long idleTime = now - lastAccess;
			if (idleTime > Math.max(GRACE_PERIOD_MS, MIN_IDLE_FOR_EVICT_MS)) {
				TextureEntry entry = textureRegistry.get(mapEntry.getKey());
				if (entry != null && !entry.isGUI && !entry.isFont) {
					toEvict.add(mapEntry.getKey());
				}
			}
		}

		for (Integer textureId : toEvict) {
			hotZoneAccessMap.remove(textureId);
			evictTexture(textureId);
		}

		if (!toEvict.isEmpty()) {
			VRAMKiller.LOGGER.info("Evicted {} cold textures due to VRAM pressure", toEvict.size());
		}
	}

	private void performWarmToColdTransition() {
		if (!running) return;

		List<Integer> toMove = new ArrayList<>();
		for (Integer textureId : warmZoneSet) {
			TextureEntry entry = textureRegistry.get(textureId);
			if (entry != null && System.currentTimeMillis() - entry.lastAccessTime > GRACE_PERIOD_MS) {
				toMove.add(textureId);
			}
		}

		for (Integer textureId : toMove) {
			warmZoneSet.remove(textureId);
			coldZoneSet.add(textureId);
			TextureEntry entry = textureRegistry.get(textureId);
			if (entry != null) {
				entry.zone = TextureZone.COLD;
			}
		}
	}

	private void evictTexture(int textureId) {
		TextureEntry entry = textureRegistry.remove(textureId);
		if (entry == null) return;

		pendingGLDeletions.add(textureId);
		evictedCount.incrementAndGet();

		long estimatedMemory = estimateTextureMemory(entry.width, entry.height, 
			entry.isCompressed, entry.format);
		totalTextureMemory.addAndGet(-estimatedMemory);

		VRAMKiller.LOGGER.debug("Queued texture {} ({}x{} compressed={}) for deletion on render thread",
			textureId, entry.width, entry.height, entry.isCompressed);
	}

	private enum TextureZone { HOT, WARM, COLD }

	public void shutdown() {
		running = false;
		scheduler.shutdown();

		try {
			if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
				scheduler.shutdownNow();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			scheduler.shutdownNow();
		}

		VRAMKiller.LOGGER.info("Texture scheduler shut down - Hot: {}, Cold: {}, Pending deletion: {}",
			hotZoneAccessMap.size(), coldZoneSet.size(), pendingGLDeletions.size());
	}

	private static class TextureEntry {
		int textureId;
		final String path;
		final int width;
		final int height;
		TextureZone zone;
		final boolean isNormalMap;
		final boolean hasTransparency;
		final boolean isGUI;
		final boolean isFont;
		final boolean isCompressed;
		final VRAMKillerConfigBase.Compression.CompressionFormat format;
		final long createTime;
		volatile long lastAccessTime;

		TextureEntry(int textureId, String path, int width, int height, TextureZone zone,
					boolean isNormalMap, boolean hasTransparency, long createTime) {
			this(textureId, path, width, height, zone, isNormalMap, hasTransparency, 
				createTime, false, null);
		}

		TextureEntry(int textureId, String path, int width, int height, TextureZone zone,
					boolean isNormalMap, boolean hasTransparency, long createTime,
					boolean isCompressed, VRAMKillerConfigBase.Compression.CompressionFormat format) {
			this.textureId = textureId;
			this.path = path;
			this.width = width;
			this.height = height;
			this.zone = zone;
			this.isNormalMap = isNormalMap;
			this.hasTransparency = hasTransparency;
			this.isGUI = path != null && (path.contains("/gui/") || path.contains("/font/"));
			this.isFont = path != null && path.contains("/font/");
			this.createTime = createTime;
			this.lastAccessTime = createTime;
			this.isCompressed = isCompressed;
			this.format = format;
		}
	}
}
