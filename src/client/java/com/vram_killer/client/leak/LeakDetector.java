package com.vram_killer.client.leak;

import com.vram_killer.VRAMKiller;
import com.vram_killer.config.VRAMKillerConfigBase;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LeakDetector {
	private static final long SUSPECT_THRESHOLD_MS = 300_000L;
	private static final long ORPHAN_THRESHOLD_MS = 600_000L;
	private static final int MAX_PENDING_DELETIONS = 256;

	private final ConcurrentHashMap<Integer, TrackedTexture> trackedTextures;
	private final ConcurrentLinkedQueue<Integer> pendingGLDeletions;
	private final ScheduledExecutorService detectorThread;
	private volatile boolean running;
	private volatile Thread renderThread;

	public LeakDetector() {
		this.trackedTextures = new ConcurrentHashMap<>();
		this.pendingGLDeletions = new ConcurrentLinkedQueue<>();
		this.detectorThread = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "VRAM-LeakDetector");
			t.setDaemon(true);
			return t;
		});
		this.running = false;
	}

	public void initialize() {
		if (!VRAMKillerConfigBase.LeakDetection.enabled) {
			VRAMKiller.LOGGER.info("Leak detection disabled in configuration");
			return;
		}

		long checkIntervalMs = VRAMKillerConfigBase.LeakDetection.checkIntervalSeconds * 1000L;
		detectorThread.scheduleAtFixedRate(this::performLeakCheck,
			checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS);

		running = true;
		VRAMKiller.LOGGER.info("Leak detector initialized - Check interval: {}s, Suspect threshold: {}ms, Orphan threshold: {}ms, Max reports: {}",
			VRAMKillerConfigBase.LeakDetection.checkIntervalSeconds, SUSPECT_THRESHOLD_MS, ORPHAN_THRESHOLD_MS, VRAMKillerConfigBase.LeakDetection.maxOrphanReports);
	}

	public void trackTextureCreation(int textureId, int width, int height, String sourcePath) {
		if (!running || textureId <= 0) return;

		TrackedTexture tracked = new TrackedTexture(textureId, width, height, sourcePath);
		trackedTextures.put(textureId, tracked);
	}

	public void trackTextureDestruction(int textureId) {
		if (textureId <= 0) return;
		trackedTextures.remove(textureId);
	}

	public void markTextureAccessed(int textureId) {
		if (textureId <= 0) return;

		TrackedTexture tracked = trackedTextures.get(textureId);
		if (tracked != null) {
			tracked.markAccessed();
		}
	}

	public void processPendingGLDeletions() {
		if (renderThread != null && Thread.currentThread() != renderThread) {
			VRAMKiller.LOGGER.debug("Skipping GL deletion - not on render thread");
			return;
		}

		int deleted = 0;
		Integer textureId;
		while ((textureId = pendingGLDeletions.poll()) != null && deleted < MAX_PENDING_DELETIONS) {
			try {
				org.lwjgl.opengl.GL11.glDeleteTextures(textureId);
				deleted++;
			} catch (Exception e) {
				VRAMKiller.LOGGER.debug("Failed to delete leaked texture {}: {}", textureId, e.getMessage());
			}
		}
		
		if (deleted > 0) {
			VRAMKiller.LOGGER.debug("Deleted {} leaked textures on render thread", deleted);
		}
	}

	public void setRenderThread(Thread thread) {
		this.renderThread = thread;
	}

	public boolean isOnRenderThread() {
		return renderThread != null && Thread.currentThread() == renderThread;
	}

	public int getTrackedCount() { return trackedTextures.size(); }

	public int getSuspectCount() {
		long now = System.currentTimeMillis();
		int count = 0;
		for (TrackedTexture tracked : trackedTextures.values()) {
			if (now - tracked.lastAccessTime > SUSPECT_THRESHOLD_MS) {
				count++;
			}
		}
		return count;
	}

	public int getOrphanCount() {
		long now = System.currentTimeMillis();
		int count = 0;
		for (TrackedTexture tracked : trackedTextures.values()) {
			if (now - tracked.lastAccessTime > ORPHAN_THRESHOLD_MS) {
				count++;
			}
		}
		return count;
	}

	public long getTotalTrackedMemory() {
		long total = 0;
		for (TrackedTexture tracked : trackedTextures.values()) {
			total += (long) tracked.width * tracked.height * 4;
		}
		return total;
	}

	public List<LeakReport> getLeakReports() {
		List<LeakReport> reports = new ArrayList<>();
		long now = System.currentTimeMillis();

		for (TrackedTexture tracked : trackedTextures.values()) {
			long age = now - tracked.createTime;
			long idle = now - tracked.lastAccessTime;

			if (idle > SUSPECT_THRESHOLD_MS) {
				LeakSeverity severity = idle > ORPHAN_THRESHOLD_MS ? LeakSeverity.ORPHAN : LeakSeverity.SUSPECT;
				reports.add(new LeakReport(
					tracked.textureId, tracked.sourcePath,
					tracked.width, tracked.height, age, idle, severity, tracked.accessCount
				));
			}
		}

		reports.sort((a, b) -> Long.compare(b.idleTime, a.idleTime));
		
		int maxReports = VRAMKillerConfigBase.LeakDetection.maxOrphanReports;
		if (reports.size() > maxReports) {
			reports = reports.subList(0, maxReports);
		}
		
		return reports;
	}

	public List<LeakReport> getPotentialLeaks() {
		return getLeakReports();
	}

	private void performLeakCheck() {
		if (!running) return;

		long now = System.currentTimeMillis();
		int suspectCount = 0;
		int orphanCount = 0;

		List<Integer> toDelete = new ArrayList<>();

		for (Map.Entry<Integer, TrackedTexture> entry : trackedTextures.entrySet()) {
			TrackedTexture tracked = entry.getValue();
			long idle = now - tracked.lastAccessTime;

			if (idle > ORPHAN_THRESHOLD_MS && tracked.accessCount < 3) {
				orphanCount++;
				if (VRAMKillerConfigBase.LeakDetection.autoCleanup && pendingGLDeletions.size() < MAX_PENDING_DELETIONS) {
					toDelete.add(entry.getKey());
				}
			} else if (idle > SUSPECT_THRESHOLD_MS && tracked.accessCount < 10) {
				suspectCount++;
			}
		}

		if (!toDelete.isEmpty()) {
			VRAMKiller.LOGGER.warn("Found {} orphan textures, queuing for deletion on render thread", toDelete.size());

			for (Integer textureId : toDelete) {
				trackedTextures.remove(textureId);
				pendingGLDeletions.add(textureId);
			}
		}

		if (suspectCount > 0 || orphanCount > 0) {
			VRAMKiller.LOGGER.info("Leak check: {} suspect, {} orphan textures, {} pending deletions", 
				suspectCount, orphanCount, pendingGLDeletions.size());
		}
	}

	public void shutdown() {
		running = false;
		detectorThread.shutdown();

		try {
			if (!detectorThread.awaitTermination(5, TimeUnit.SECONDS)) {
				detectorThread.shutdownNow();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			detectorThread.shutdownNow();
		}

		VRAMKiller.LOGGER.info("Leak detector shut down - Tracked: {}, Pending deletions: {}",
			trackedTextures.size(), pendingGLDeletions.size());
	}

	private static class TrackedTexture {
		final int textureId;
		final int width;
		final int height;
		final String sourcePath;
		final long createTime;
		volatile long lastAccessTime;
		volatile long accessCount;

		TrackedTexture(int textureId, int width, int height, String sourcePath) {
			this.textureId = textureId;
			this.width = width;
			this.height = height;
			this.sourcePath = sourcePath;
			this.createTime = System.currentTimeMillis();
			this.lastAccessTime = this.createTime;
			this.accessCount = 0;
		}

		void markAccessed() {
			this.lastAccessTime = System.currentTimeMillis();
			this.accessCount++;
		}
	}

	public enum LeakSeverity { SUSPECT, ORPHAN }

	public static class LeakReport {
		public final int textureId;
		public final String sourcePath;
		public final int width;
		public final int height;
		public final long age;
		public final long idleTime;
		public final LeakSeverity severity;
		public final long accessCount;

		public LeakReport(int textureId, String sourcePath, int width, int height,
						 long age, long idleTime, LeakSeverity severity, long accessCount) {
			this.textureId = textureId;
			this.sourcePath = sourcePath;
			this.width = width;
			this.height = height;
			this.age = age;
			this.idleTime = idleTime;
			this.severity = severity;
			this.accessCount = accessCount;
		}

		public String formatReport() {
			return String.format("[%s] id=%d %s (%dx%d) idle=%ds accesses=%d",
				severity, textureId, sourcePath, width, height, idleTime / 1000, accessCount);
		}
	}
}
