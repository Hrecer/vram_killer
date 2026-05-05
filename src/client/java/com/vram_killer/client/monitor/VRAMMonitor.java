package com.vram_killer.client.monitor;

import com.vram_killer.VRAMKiller;
import com.vram_killer.client.VRAMKillerClient;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.File;
import java.nio.IntBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class VRAMMonitor {
	private static final long SAMPLE_INTERVAL_MS = 2000;
	private static final long NVIDIA_SMI_INTERVAL_MS = 5_000;

	private static final int GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX = 0x9055;
	private static final int GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX = 0x9056;
	private static final int GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX = 0x9054;
	private static final int GL_GPU_MEMORY_INFO_EVICTION_COUNT_NVX = 0x9057;
	private static final int GL_GPU_MEMORY_INFO_EVICTED_MEMORY_NVX = 0x9058;
	private static final int GL_VBO_FREE_MEMORY_ATI = 0x87FC;
	private static final int GL_TEXTURE_FREE_MEMORY_ATI = 0x87FB;
	private static final int GL_RENDERBUFFER_FREE_MEMORY_ATI = 0x87FD;

	private final ScheduledExecutorService samplerService;
	private final AtomicLong totalVRAMBudget;
	private final AtomicLong currentVRAMUsage;
	private final AtomicLong currentAvailableVRAM;
	private final AtomicReference<Sample> latestSample;
	private final RingBuffer samplesHistory;

	private volatile boolean sampling;
	private volatile boolean vramDetected;
	private volatile String gpuRenderer;
	private volatile String gpuVendor;

	private volatile VRAMQueryMethod queryMethod = VRAMQueryMethod.NONE;
	private volatile long dedicatedVRAMKB = -1;
	private volatile long totalAvailableVRAMKB = -1;
	private volatile long currentAvailableVRAMKB = -1;
	private volatile long evictionCount = -1;
	private volatile long evictedMemoryKB = -1;

	private volatile long lastNvidiaSmiTime = 0;
	private volatile String cachedNvidiaSmiPath = null;
	private volatile int consecutiveSmiFailures = 0;

	private enum VRAMQueryMethod {
		NONE,
		NVX_GPU_MEMORY_INFO,
		ATI_MEMINFO,
		WINDOWS_API
	}

	public VRAMMonitor() {
		this.samplerService = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "VRAM-Monitor");
			t.setDaemon(true);
			return t;
		});
		this.totalVRAMBudget = new AtomicLong(0);
		this.currentVRAMUsage = new AtomicLong(0);
		this.currentAvailableVRAM = new AtomicLong(0);
		this.latestSample = new AtomicReference<>(new Sample(0, 0, 0));
		this.samplesHistory = new RingBuffer(60);
		this.sampling = false;
		this.vramDetected = false;
	}

	public void start() {
		samplerService.scheduleAtFixedRate(this::takeSample,
			SAMPLE_INTERVAL_MS, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);

		sampling = true;
		VRAMKiller.LOGGER.info("VRAM monitor started (GPU detection will occur on first render)");
	}

	public void detectVRAMInfoFromRenderThread() {
		if (vramDetected) return;

		try {
			String renderer = GL11.glGetString(GL11.GL_RENDERER);
			String vendor = GL11.glGetString(GL11.GL_VENDOR);

			this.gpuRenderer = renderer;
			this.gpuVendor = vendor;

			if (renderer == null || vendor == null) {
				VRAMKiller.LOGGER.warn("Could not get GPU info from OpenGL");
				return;
			}

			detectQueryMethod();

			if (queryMethod == VRAMQueryMethod.NVX_GPU_MEMORY_INFO) {
				readNvidiaVRAMInfo();
			} else if (queryMethod == VRAMQueryMethod.ATI_MEMINFO) {
				readATIVRAMInfo();
			}

			if (dedicatedVRAMKB > 0) {
				totalVRAMBudget.set(dedicatedVRAMKB * 1024L);
			} else if (totalAvailableVRAMKB > 0) {
				totalVRAMBudget.set(totalAvailableVRAMKB * 1024L);
			}

			vramDetected = true;

			VRAMKiller.LOGGER.info("GPU: {} {} | Query: {} | Dedicated: {} MB | Budget: {} MB",
				vendor, renderer, queryMethod,
				dedicatedVRAMKB > 0 ? dedicatedVRAMKB / 1024 : "?",
				totalVRAMBudget.get() / (1024 * 1024));

		} catch (Exception e) {
			VRAMKiller.LOGGER.warn("VRAM detection failed: {}", e.getMessage());
		}
	}

	private void detectQueryMethod() {
		try {
			boolean hasNVX = checkExtensionModern("GL_NVX_gpu_memory_info");
			boolean hasATI = checkExtensionModern("GL_ATI_meminfo");

			if (hasNVX) {
				IntBuffer test = IntBuffer.allocate(1);
				try {
					GL30.glGetIntegerv(GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX, test);
					if (test.get(0) > 0) {
						queryMethod = VRAMQueryMethod.NVX_GPU_MEMORY_INFO;
						VRAMKiller.LOGGER.info("GL_NVX_gpu_memory_info extension available and functional (dedicated: {} KB)", test.get(0));
						return;
					}
				} catch (Exception ignored) {}
			}

			if (hasATI) {
				IntBuffer test = IntBuffer.allocate(4);
				try {
					GL30.glGetIntegerv(GL_TEXTURE_FREE_MEMORY_ATI, test);
					if (test.get(0) > 0) {
						queryMethod = VRAMQueryMethod.ATI_MEMINFO;
						VRAMKiller.LOGGER.info("GL_ATI_meminfo extension available and functional");
						return;
					}
				} catch (Exception ignored) {}
			}

			if (tryReadVRAMFromWindowsAPI()) {
				VRAMKiller.LOGGER.info("Using Windows API (nvidia-smi) for VRAM monitoring (background thread)");
				return;
			}

			VRAMKiller.LOGGER.warn("No VRAM query method available - real-time VRAM reading not possible on this system");
			queryMethod = VRAMQueryMethod.NONE;

		} catch (Exception e) {
			VRAMKiller.LOGGER.warn("GL extension check failed: {}", e.getMessage());
			queryMethod = VRAMQueryMethod.NONE;
		}
	}

	private boolean tryReadVRAMFromWindowsAPI() {
		if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
			return false;
		}

		String smiPath = findNvidiaSmi();
		if (smiPath == null) return false;

		cachedNvidiaSmiPath = smiPath;

		long[] vramInfo = queryVRAMViaNvidiaSmi(smiPath);
		if (vramInfo != null && vramInfo[0] > 0) {
			dedicatedVRAMKB = vramInfo[0] / 1024L;
			currentAvailableVRAMKB = vramInfo[1] / 1024L;
			totalAvailableVRAMKB = dedicatedVRAMKB;
			queryMethod = VRAMQueryMethod.WINDOWS_API;

			samplerService.scheduleAtFixedRate(this::updateVRAMFromBackgroundThread,
				NVIDIA_SMI_INTERVAL_MS, NVIDIA_SMI_INTERVAL_MS, TimeUnit.MILLISECONDS);

			return true;
		}

		return false;
	}

	private void updateVRAMFromBackgroundThread() {
		if (queryMethod != VRAMQueryMethod.WINDOWS_API) return;

		if (consecutiveSmiFailures > 3) {
			consecutiveSmiFailures = 0;
			return;
		}

		try {
			long[] vramInfo = queryVRAMViaNvidiaSmi(cachedNvidiaSmiPath);
			if (vramInfo != null && vramInfo[1] >= 0) {
				currentAvailableVRAMKB = vramInfo[1] / 1024L;
				consecutiveSmiFailures = 0;
			} else {
				consecutiveSmiFailures++;
			}
		} catch (Exception e) {
			consecutiveSmiFailures++;
			VRAMKiller.LOGGER.debug("Background nvidia-smi query failed: {}", e.getMessage());
		}
	}

	private long[] queryVRAMViaNvidiaSmi(String smiPath) {
		if (smiPath == null) return null;

		try {
			ProcessBuilder pb = new ProcessBuilder(
				smiPath,
				"--query-gpu=memory.total,memory.free,memory.used",
				"--format=csv,noheader,nounits"
			);
			pb.redirectErrorStream(true);
			Process process = pb.start();
			process.waitFor(5, TimeUnit.SECONDS);

			String output = new String(process.getInputStream().readAllBytes()).trim();
			if (output.isEmpty()) return null;

			String[] parts = output.split(",");
			if (parts.length >= 3) {
				long totalMB = Long.parseLong(parts[0].trim());
				long freeMB = Long.parseLong(parts[1].trim());
				long usedMB = Long.parseLong(parts[2].trim());

				VRAMKiller.LOGGER.debug("nvidia-smi - Total: {} MB | Free: {} MB | Used: {} MB",
					totalMB, freeMB, usedMB);

				return new long[]{ totalMB * 1024L * 1024L, freeMB * 1024L * 1024L };
			}
		} catch (Exception e) {
			VRAMKiller.LOGGER.debug("nvidia-smi query failed: {}", e.getMessage());
		}
		return null;
	}

	private String findNvidiaSmi() {
		if (gpuVendor == null || !gpuVendor.toLowerCase().contains("nvidia")) {
			return null;
		}

		String[] searchPaths = {
			"C:\\Windows\\System32\\nvidia-smi.exe",
			System.getenv("ProgramFiles") + "\\NVIDIA Corporation\\NVSMI\\nvidia-smi.exe",
			"C:\\Program Files\\NVIDIA Corporation\\NVSMI\\nvidia-smi.exe",
		};

		for (String path : searchPaths) {
			if (path != null && new File(path).exists()) {
				return path;
			}
		}

		try {
			ProcessBuilder which = new ProcessBuilder("where", "nvidia-smi");
			which.redirectErrorStream(true);
			Process p = which.start();
			p.waitFor(3, TimeUnit.SECONDS);
			String result = new String(p.getInputStream().readAllBytes()).trim();
			if (!result.isEmpty() && result.contains("nvidia-smi")) {
				return result.split("\n")[0].trim();
			}
		} catch (Exception ignored) {}

		return null;
	}

	private boolean checkExtensionModern(String extensionName) {
		try {
			String extensions = GL11.glGetString(GL11.GL_EXTENSIONS);
			if (extensions != null && extensions.contains(extensionName)) {
				return true;
			}
		} catch (Exception ignored) {}

		try {
			int numExtensions = GL30.glGetInteger(GL30.GL_NUM_EXTENSIONS);
			for (int i = 0; i < numExtensions; i++) {
				String ext = GL30.glGetStringi(GL30.GL_EXTENSIONS, i);
				if (extensionName.equals(ext)) {
					return true;
				}
			}
		} catch (Exception ignored) {}

		return false;
	}

	private void readNvidiaVRAMInfo() {
		try {
			IntBuffer dedicatedBuf = IntBuffer.allocate(1);
			IntBuffer totalAvailBuf = IntBuffer.allocate(1);
			IntBuffer currentAvailBuf = IntBuffer.allocate(1);

			GL30.glGetIntegerv(GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX, dedicatedBuf);
			GL30.glGetIntegerv(GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX, totalAvailBuf);
			GL30.glGetIntegerv(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX, currentAvailBuf);

			dedicatedVRAMKB = dedicatedBuf.get(0) & 0xFFFFFFFFL;
			totalAvailableVRAMKB = totalAvailBuf.get(0) & 0xFFFFFFFFL;
			currentAvailableVRAMKB = currentAvailBuf.get(0) & 0xFFFFFFFFL;

			VRAMKiller.LOGGER.info("NVIDIA VRAM - Dedicated: {} KB | Total Available: {} KB | Current Free: {} KB",
				dedicatedVRAMKB, totalAvailableVRAMKB, currentAvailableVRAMKB);

		} catch (Exception e) {
			VRAMKiller.LOGGER.warn("Failed to read NVIDIA VRAM info: {}", e.getMessage());
		}
	}

	private void readATIVRAMInfo() {
		try {
			IntBuffer texFree = IntBuffer.allocate(4);
			IntBuffer vboFree = IntBuffer.allocate(4);
			IntBuffer rbFree = IntBuffer.allocate(4);

			GL30.glGetIntegerv(GL_TEXTURE_FREE_MEMORY_ATI, texFree);
			GL30.glGetIntegerv(GL_VBO_FREE_MEMORY_ATI, vboFree);
			GL30.glGetIntegerv(GL_RENDERBUFFER_FREE_MEMORY_ATI, rbFree);

			long texFreeKB = texFree.get(0) & 0xFFFFFFFFL;
			long vboFreeKB = vboFree.get(0) & 0xFFFFFFFFL;
			long rbFreeKB = rbFree.get(0) & 0xFFFFFFFFL;

			currentAvailableVRAMKB = texFreeKB + vboFreeKB + rbFreeKB;

			VRAMKiller.LOGGER.info("AMD VRAM - Texture free: {} KB | VBO free: {} KB | RB free: {} KB",
				texFreeKB, vboFreeKB, rbFreeKB);

		} catch (Exception e) {
			VRAMKiller.LOGGER.warn("Failed to read AMD VRAM info: {}", e.getMessage());
		}
	}

	public void updateAvailableVRAMFromRenderThread() {
		if (!vramDetected) return;

		try {
			if (queryMethod == VRAMQueryMethod.NVX_GPU_MEMORY_INFO) {
				IntBuffer currentAvailBuf = IntBuffer.allocate(1);
				GL30.glGetIntegerv(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX, currentAvailBuf);
				currentAvailableVRAMKB = currentAvailBuf.get(0) & 0xFFFFFFFFL;

				IntBuffer totalAvailBuf = IntBuffer.allocate(1);
				GL30.glGetIntegerv(GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX, totalAvailBuf);
				totalAvailableVRAMKB = totalAvailBuf.get(0) & 0xFFFFFFFFL;

			} else if (queryMethod == VRAMQueryMethod.ATI_MEMINFO) {
				IntBuffer texFree = IntBuffer.allocate(4);
				IntBuffer vboFree = IntBuffer.allocate(4);
				IntBuffer rbFree = IntBuffer.allocate(4);

				GL30.glGetIntegerv(GL_TEXTURE_FREE_MEMORY_ATI, texFree);
				GL30.glGetIntegerv(GL_VBO_FREE_MEMORY_ATI, vboFree);
				GL30.glGetIntegerv(GL_RENDERBUFFER_FREE_MEMORY_ATI, rbFree);

				currentAvailableVRAMKB = (texFree.get(0) & 0xFFFFFFFFL) +
					(vboFree.get(0) & 0xFFFFFFFFL) +
					(rbFree.get(0) & 0xFFFFFFFFL);
			}
		} catch (Exception e) {
			VRAMKiller.LOGGER.debug("Failed to update VRAM from GL: {}", e.getMessage());
		}
	}

	public double getCurrentVRAMUsagePercent() {
		long budget = totalVRAMBudget.get();
		long usage = currentVRAMUsage.get();
		if (budget <= 0) return 0.0;
		return (double) usage / budget * 100.0;
	}

	public long getCurrentVRAMUsageBytes() { return currentVRAMUsage.get(); }
	public long getVRAMBudgetBytes() { return totalVRAMBudget.get(); }
	public long getCurrentAvailableVRAMBytes() { return currentAvailableVRAM.get(); }
	public int getVRAMUsageMB() { return (int) (currentVRAMUsage.get() / (1024L * 1024L)); }
	public int getVRAMBudgetMB() { return (int) (totalVRAMBudget.get() / (1024L * 1024L)); }
	public int getAvailableVRAMMB() { return (int) (currentAvailableVRAM.get() / (1024L * 1024L)); }
	public long getEvictionCount() { return evictionCount; }
	public String getQueryMethodName() { return queryMethod.name(); }
	public Sample getLatestSample() { return latestSample.get(); }
	public Sample[] getRecentSamples(int count) { return samplesHistory.getRecent(count); }

	public double getAverageUsagePercent(int sampleCount) {
		Sample[] samples = getRecentSamples(sampleCount);
		if (samples.length == 0) return 0.0;
		double sum = 0;
		for (Sample s : samples) { sum += s.usagePercent; }
		return sum / samples.length;
	}

	public boolean isVRAMCritical() {
		return getCurrentVRAMUsagePercent() > com.vram_killer.config.VRAMKillerConfigBase.Scheduler.maxVRAMUsagePercent;
	}

	public boolean isVRAMWarning() { return getCurrentVRAMUsagePercent() > 80.0; }
	public boolean isVRAMDetected() { return vramDetected; }
	public String getGpuRenderer() { return gpuRenderer; }
	public String getGpuVendor() { return gpuVendor; }

	private void takeSample() {
		long usage = calculateUsage();
		long budget = totalVRAMBudget.get();
		double percent = budget > 0 ? (double) usage / budget * 100.0 : 0;

		Sample sample = new Sample(System.currentTimeMillis(), usage, percent);
		latestSample.set(sample);
		samplesHistory.add(sample);
		currentVRAMUsage.set(usage);

		if (percent > 95) {
			VRAMKiller.LOGGER.warn("Critical VRAM usage: " + String.format("%.1f%%", percent)
				+ " (" + getVRAMUsageMB() + " / " + getVRAMBudgetMB() + " MB)");
		}
	}

	private long calculateUsage() {
		if (queryMethod == VRAMQueryMethod.NVX_GPU_MEMORY_INFO && totalAvailableVRAMKB > 0) {
			long usedKB = totalAvailableVRAMKB - currentAvailableVRAMKB;
			if (usedKB > 0) {
				currentAvailableVRAM.set(currentAvailableVRAMKB * 1024L);
				return usedKB * 1024L;
			}
		}

		if (queryMethod == VRAMQueryMethod.ATI_MEMINFO && currentAvailableVRAMKB >= 0) {
			long budgetKB = totalVRAMBudget.get() / 1024L;
			if (budgetKB > 0) {
				long usedKB = budgetKB - currentAvailableVRAMKB;
				if (usedKB > 0) {
					currentAvailableVRAM.set(currentAvailableVRAMKB * 1024L);
					return usedKB * 1024L;
				}
			}
		}

		if (queryMethod == VRAMQueryMethod.WINDOWS_API && dedicatedVRAMKB > 0 && currentAvailableVRAMKB >= 0) {
			long usedKB = dedicatedVRAMKB - currentAvailableVRAMKB;
			if (usedKB > 0) {
				currentAvailableVRAM.set(currentAvailableVRAMKB * 1024L);
				return usedKB * 1024L;
			}
		}

		long tracked = getTrackedTextureMemoryEstimate();
		if (tracked > 0) {
			return tracked;
		}

		return currentVRAMUsage.get();
	}

	private long getTrackedTextureMemoryEstimate() {
		try {
			var mgr = VRAMKillerClient.getVRAMManager();
			if (mgr != null) {
				var sched = mgr.getTextureScheduler();
				if (sched != null) {
					return sched.getTotalEstimatedMemory();
				}
			}
		} catch (Exception ignored) {}
		return 0;
	}

	public void shutdown() {
		sampling = false;
		samplerService.shutdown();
		VRAMKiller.LOGGER.info("VRAM monitor shut down - Final usage: " + String.format("%.1f%%", getCurrentVRAMUsagePercent())
			+ " (" + getVRAMUsageMB() + " / " + getVRAMBudgetMB() + " MB)");
	}

	public static class Sample {
		final long timestamp;
		final long usageBytes;
		final double usagePercent;

		public Sample(long timestamp, long usageBytes, double usagePercent) {
			this.timestamp = timestamp;
			this.usageBytes = usageBytes;
			this.usagePercent = usagePercent;
		}

		public int getUsageMB() { return (int) (usageBytes / (1024L * 1024L)); }
	}

	private static class RingBuffer {
		private final Sample[] buffer;
		private int index;
		private int count;

		public RingBuffer(int capacity) {
			this.buffer = new Sample[capacity];
			this.index = 0;
			this.count = 0;
		}

		public synchronized void add(Sample sample) {
			buffer[index] = sample;
			index = (index + 1) % buffer.length;
			count = Math.min(count + 1, buffer.length);
		}

		public synchronized Sample[] getRecent(int maxCount) {
			int size = Math.min(count, maxCount);
			Sample[] result = new Sample[size];
			for (int i = 0; i < size; i++) {
				int idx = (index - 1 - i + buffer.length) % buffer.length;
				result[i] = buffer[idx];
			}
			return result;
		}
	}
}
