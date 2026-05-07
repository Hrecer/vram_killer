package com.vram_killer.client.compatibility;

import com.vram_killer.VRAMKiller;
import com.vram_killer.config.VRAMKillerConfigBase;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CompatibilityChecker {
	private static final Set<String> INCOMPATIBLE_MODS = Set.of(
		"optifine", "optifabric", "optiforge"
	);

	private final Map<String, ModInfo> detectedMods;
	private final GPUInfo gpuInfo;
	private final ConcurrentHashMap<String, Boolean> featureSupport;

	private boolean sodiumDetected;
	private boolean irisDetected;
	private boolean embeddiumDetected;

	public CompatibilityChecker() {
		this.detectedMods = new HashMap<>();
		this.gpuInfo = new GPUInfo();
		this.featureSupport = new ConcurrentHashMap<>();

		detectEnvironment();
	}

	public void checkCompatibility() {
		VRAMKiller.LOGGER.info("Starting compatibility check...");

		checkModConflicts();
		checkGPUCompatibility();
		determineFeatureSupport();

		if (sodiumDetected || embeddiumDetected) {
			initializeSodiumIntegration();
		}

		logCompatibilityReport();
	}

	private void initializeSodiumIntegration() {
		try {
			SodiumIntegrationBridge.detectAndInitialize();

			featureSupport.put("sodium_memory_tracking", SodiumIntegrationBridge.isMemoryTrackingAvailable());

			VRAMKiller.LOGGER.info("Sodium integration - Detected: {} | Memory tracking: {}",
				SodiumIntegrationBridge.isSodiumDetected(), SodiumIntegrationBridge.isMemoryTrackingAvailable());

		} catch (Exception e) {
			VRAMKiller.LOGGER.warn("Failed to initialize Sodium integration: {}", e.getMessage());
			featureSupport.put("sodium_integration_failed", true);
		}
	}

	private void detectEnvironment() {
		sodiumDetected = isModLoaded("sodium");
		irisDetected = isModLoaded("iris");
		embeddiumDetected = isModLoaded("embeddium");

		VRAMKiller.LOGGER.info("Environment - Sodium: {} | Iris: {} | Embeddium: {}",
			sodiumDetected, irisDetected, embeddiumDetected);
	}

	private void checkModConflicts() {
		List<String> conflicts = new ArrayList<>();

		for (String modId : INCOMPATIBLE_MODS) {
			if (isModLoaded(modId)) {
				conflicts.add(modId);

				disableOverlappingFeatures(modId);
			}
		}

		if (!conflicts.isEmpty()) {
			VRAMKiller.LOGGER.warn("[!] Incompatible mods detected: {} - Some features disabled",
				String.join(", ", conflicts));
		}
	}

	private void disableOverlappingFeatures(String modId) {
		switch (modId.toLowerCase()) {
			case "optifine":
			case "optifabric":
				featureSupport.put("compression_enabled", false);
				featureSupport.put("texture_interception", false);
				break;

			default:
				featureSupport.put(modId + "_conflict", true);
				break;
		}
	}

	private void checkGPUCompatibility() {
		gpuInfo.detect();

		String vendor = gpuInfo.getVendor().toLowerCase();
		String renderer = gpuInfo.getRenderer().toLowerCase();

		if (vendor.contains("amd") || vendor.contains("ati")) {
			setupAMDOptimizations();
		} else if (vendor.contains("nvidia")) {
			setUpNvidiaOptimizations();
		} else if (vendor.contains("intel")) {
			setupIntelOptimizations();
		}
	}

	private void setupAMDOptimizations() {
		featureSupport.put("amd_texture_alignment", true);
		featureSupport.put("amd_driver_timeout_workaround", true);
		VRAMKiller.LOGGER.info("AMD GPU detected - texture alignment and driver timeout workarounds enabled");
	}

	private void setUpNvidiaOptimizations() {
		featureSupport.put("nvidia_persistent_buffers", true);
		VRAMKiller.LOGGER.info("NVIDIA GPU detected - persistent buffer optimization enabled");
	}

	private void setupIntelOptimizations() {
		featureSupport.put("intel_shared_memory_aware", true);
		VRAMKiller.LOGGER.info("Intel GPU detected - shared memory awareness enabled");
	}

	private void determineFeatureSupport() {
		featureSupport.putIfAbsent("bc7_compression", supportsExtension("GL_ARB_texture_compression_bptc"));
		featureSupport.putIfAbsent("bc5_compression", supportsExtension("GL_ARB_texture_compression_rgtc"));
		featureSupport.putIfAbsent("pbo_async_upload", supportsPBO());
		featureSupport.putIfAbsent("texture_storage", supportsTextureStorage());
		featureSupport.putIfAbsent("debug_output", supportsDebugOutput());

		featureSupport.putIfAbsent("sodium_integration", sodiumDetected || embeddiumDetected);
		featureSupport.putIfAbsent("iris_integration", irisDetected);
	}

	private boolean supportsExtension(String extensionName) {
		try {
			String extensions = GL11.glGetString(GL11.GL_EXTENSIONS);
			return extensions != null && extensions.contains(extensionName);
		} catch (Exception e) {
			return false;
		}
	}

	private boolean supportsPBO() {
		int version = getOpenGLVersion();
		return version >= 21;
	}

	private boolean supportsTextureStorage() {
		int version = getOpenGLVersion();
		return version >= 42 || supportsExtension("GL_ARB_texture_storage");
	}

	private boolean supportsDebugOutput() {
		return supportsExtension("GL_KHR_debug") || supportsExtension("GL_ARB_debug_output");
	}

	private int getOpenGLVersion() {
		try {
			String versionStr = GL11.glGetString(GL11.GL_VERSION);
			if (versionStr != null && versionStr.matches("\\d+\\.\\d+.*")) {
				String majorMinor = versionStr.substring(0, versionStr.indexOf('.') + 2)
					.replaceAll("[^\\d.]", "");
				return (int)(Double.parseDouble(majorMinor) * 10);
			}
		} catch (Exception ignored) {}
		return 20;
	}

	private boolean isModLoaded(String modId) {
		try {
			net.fabricmc.loader.api.FabricLoader loader =
				net.fabricmc.loader.api.FabricLoader.getInstance();

			return loader.isModLoaded(modId) ||
				   loader.getModContainer(modId).isPresent();

		} catch (Exception e) {
			return false;
		}
	}

	private void logCompatibilityReport() {
		StringBuilder report = new StringBuilder();
		report.append("\n=== VRAM Killer Compatibility Report ===\n\n");

		report.append(String.format("GPU: %s %s\n", gpuInfo.getVendor(), gpuInfo.getRenderer()));
		report.append(String.format("OpenGL: %s\n", GL11.glGetString(GL11.GL_VERSION)));
		report.append(String.format("VRAM Budget: %d MB\n\n", gpuInfo.getEstimatedVRAMMB()));

		report.append("Feature Support:\n");
		for (Map.Entry<String, Boolean> entry : featureSupport.entrySet()) {
			String status = entry.getValue() ? "[OK]" : "[NO]";
			report.append(String.format("  %s %s\n", status, entry.getKey()));
		}

		report.append("\n=== End Report ===\n");

		VRAMKiller.LOGGER.info(report.toString());
	}

	public boolean isFeatureEnabled(String featureName) {
		return featureSupport.getOrDefault(featureName, false);
	}

	public boolean hasConflicts() {
		return featureSupport.entrySet().stream().anyMatch(e -> e.getKey().contains("_conflict") && Boolean.TRUE.equals(e.getValue()));
	}

	public boolean isSodiumAvailable() { return sodiumDetected; }
	public boolean isIrisAvailable() { return irisDetected; }
	public boolean isEmbeddiumAvailable() { return embeddiumDetected; }

	public GPUInfo getGPUInfo() { return gpuInfo; }

	public static class ModInfo {
		final String id;
		final String version;
		final String source;

		public ModInfo(String id, String version, String source) {
			this.id = id;
			this.version = version;
			this.source = source;
		}
	}

	public static class GPUInfo {
		private String vendor;
		private String renderer;
		private int vramMB;

		public void detect() {
			try {
				vendor = GL11.glGetString(GL11.GL_VENDOR);
				renderer = GL11.glGetString(GL11.GL_RENDERER);
				vramMB = readVRAMFromGL();
			} catch (Exception e) {
				vendor = "Unknown";
				renderer = "Unknown";
				vramMB = 0;
			}
		}

		private int readVRAMFromGL() {
			try {
				String extensions = GL11.glGetString(GL11.GL_EXTENSIONS);
				if (extensions != null && extensions.contains("GL_NVX_gpu_memory_info")) {
					IntBuffer dedicatedBuf = IntBuffer.allocate(1);
					GL30.glGetIntegerv(0x9054, dedicatedBuf);
					long dedicatedKB = dedicatedBuf.get(0) & 0xFFFFFFFFL;
					if (dedicatedKB > 0) {
						return (int) (dedicatedKB / 1024);
					}
				}

				if (extensions != null && extensions.contains("GL_ATI_meminfo")) {
					IntBuffer texFree = IntBuffer.allocate(4);
					IntBuffer vboFree = IntBuffer.allocate(4);
					IntBuffer rbFree = IntBuffer.allocate(4);
					GL30.glGetIntegerv(0x87FB, texFree);
					GL30.glGetIntegerv(0x87FC, vboFree);
					GL30.glGetIntegerv(0x87FD, rbFree);

					long totalFreeKB = (texFree.get(0) & 0xFFFFFFFFL) +
						(vboFree.get(0) & 0xFFFFFFFFL) +
						(rbFree.get(0) & 0xFFFFFFFFL);

					if (totalFreeKB > 0) {
						return -1;
					}
				}
			} catch (Exception ignored) {}
			return 0;
		}

		public String getVendor() { return vendor; }
		public String getRenderer() { return renderer; }
		public int getEstimatedVRAMMB() { return vramMB; }
	}
}
