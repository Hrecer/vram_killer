package com.vram_killer.config;

import com.vram_killer.VRAMKiller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class VRAMConfig {
	private static final String CONFIG_DIR = "config/vram_killer";
	private static final String CONFIG_FILE = "vram_killer_config.toml";
	private static final Path CONFIG_PATH = Path.of(CONFIG_DIR, CONFIG_FILE);

	private static final CopyOnWriteArrayList<Runnable> reloadListeners = new CopyOnWriteArrayList<>();
	private static volatile boolean loaded = false;

	public static void load() {
		try {
			Files.createDirectories(Path.of(CONFIG_DIR));
		} catch (IOException e) {
			VRAMKiller.LOGGER.error("Failed to create config directory: {}", e.getMessage());
			return;
		}

		if (!Files.exists(CONFIG_PATH)) {
			save();
			VRAMKiller.LOGGER.info("Created default config at {}", CONFIG_PATH);
		}

		Map<String, String> values = parseTOML(CONFIG_PATH);
		applyValues(values);

		VRAMKillerConfigBase.validate();
		loaded = true;
		VRAMKiller.LOGGER.info("Config loaded from {}", CONFIG_PATH);
	}

	public static void save() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			String content = generateTOML();
			Files.writeString(CONFIG_PATH, content);
		} catch (IOException e) {
			VRAMKiller.LOGGER.error("Failed to save config: {}", e.getMessage());
		}
	}

	public static void reload() {
		if (Files.exists(CONFIG_PATH)) {
			Map<String, String> values = parseTOML(CONFIG_PATH);
			applyValues(values);
			VRAMKillerConfigBase.validate();
			VRAMKiller.LOGGER.info("Config reloaded");
		}
		for (Runnable listener : reloadListeners) {
			try {
				listener.run();
			} catch (Exception e) {
				VRAMKiller.LOGGER.error("Config reload listener error", e);
			}
		}
	}

	public static void addReloadListener(Runnable listener) {
		reloadListeners.add(listener);
	}

	public static void removeReloadListener(Runnable listener) {
		reloadListeners.remove(listener);
	}

	public static boolean isLoaded() {
		return loaded;
	}

	public static Path getConfigPath() {
		return CONFIG_PATH;
	}

	private static Map<String, String> parseTOML(Path path) {
		Map<String, String> map = new HashMap<>();
		try {
			String section = "";
			for (String rawLine : Files.readAllLines(path)) {
				String line = rawLine.trim();
				if (line.isEmpty() || line.startsWith("#")) continue;
				if (line.startsWith("[") && line.endsWith("]")) {
					section = line.substring(1, line.length() - 1).trim() + ".";
					continue;
				}
				int eqIdx = line.indexOf('=');
				if (eqIdx < 0) continue;
				String key = section + line.substring(0, eqIdx).trim();
				String val = line.substring(eqIdx + 1).trim();
				if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
					val = val.substring(1, val.length() - 1);
				}
				map.put(key, val);
			}
		} catch (IOException e) {
			VRAMKiller.LOGGER.error("Failed to parse config: {}", e.getMessage());
		}
		return map;
	}

	private static void applyValues(Map<String, String> values) {
		VRAMKillerConfigBase.Display.DebugOverlay.enabled = getBool(values, "display.debugOverlay.enabled", VRAMKillerConfigBase.Display.DebugOverlay.enabled);
		VRAMKillerConfigBase.Display.DebugOverlay.showVRAMUsage = getBool(values, "display.debugOverlay.showVRAMUsage", VRAMKillerConfigBase.Display.DebugOverlay.showVRAMUsage);
		VRAMKillerConfigBase.Display.DebugOverlay.showConversionStats = getBool(values, "display.debugOverlay.showConversionStats", VRAMKillerConfigBase.Display.DebugOverlay.showConversionStats);
		VRAMKillerConfigBase.Display.DebugOverlay.showTextureStats = getBool(values, "display.debugOverlay.showTextureStats", VRAMKillerConfigBase.Display.DebugOverlay.showTextureStats);
		VRAMKillerConfigBase.Display.DebugOverlay.showLeakDetection = getBool(values, "display.debugOverlay.showLeakDetection", VRAMKillerConfigBase.Display.DebugOverlay.showLeakDetection);
		VRAMKillerConfigBase.Display.DebugOverlay.showAtlasStats = getBool(values, "display.debugOverlay.showAtlasStats", VRAMKillerConfigBase.Display.DebugOverlay.showAtlasStats);

		VRAMKillerConfigBase.Conversion.enabled = getBool(values, "conversion.enabled", VRAMKillerConfigBase.Conversion.enabled);
		VRAMKillerConfigBase.Conversion.rgb5a1Conversion = getBool(values, "conversion.rgb5a1Conversion", VRAMKillerConfigBase.Conversion.rgb5a1Conversion);
		VRAMKillerConfigBase.Conversion.minTextureSize = getInt(values, "conversion.minTextureSize", VRAMKillerConfigBase.Conversion.minTextureSize, 2, 64);
		VRAMKillerConfigBase.Conversion.asyncProcessing = getBool(values, "conversion.asyncProcessing", VRAMKillerConfigBase.Conversion.asyncProcessing);

		VRAMKillerConfigBase.Cache.enabled = getBool(values, "cache.enabled", VRAMKillerConfigBase.Cache.enabled);
		VRAMKillerConfigBase.Cache.maxSizeMB = getInt(values, "cache.maxSizeMB", VRAMKillerConfigBase.Cache.maxSizeMB, 256, 8192);
		VRAMKillerConfigBase.Cache.enableColdZone = getBool(values, "cache.enableColdZone", VRAMKillerConfigBase.Cache.enableColdZone);

		VRAMKillerConfigBase.Scheduler.enabled = getBool(values, "scheduler.enabled", VRAMKillerConfigBase.Scheduler.enabled);
		VRAMKillerConfigBase.Scheduler.coldZoneDelaySeconds = getInt(values, "scheduler.coldZoneDelaySeconds", VRAMKillerConfigBase.Scheduler.coldZoneDelaySeconds, 10, 300);
		VRAMKillerConfigBase.Scheduler.maxVRAMUsagePercent = getInt(values, "scheduler.maxVRAMUsagePercent", VRAMKillerConfigBase.Scheduler.maxVRAMUsagePercent, 50, 99);

		VRAMKillerConfigBase.Mipmap.minMipmapSize16x16 = getInt(values, "mipmap.minMipmapSize16x16", VRAMKillerConfigBase.Mipmap.minMipmapSize16x16, 0, 4);
		VRAMKillerConfigBase.Mipmap.minMipmapSizeHD = getInt(values, "mipmap.minMipmapSizeHD", VRAMKillerConfigBase.Mipmap.minMipmapSizeHD, 0, 4);
		VRAMKillerConfigBase.Mipmap.disableGUIMipmaps = getBool(values, "mipmap.disableGUIMipmaps", VRAMKillerConfigBase.Mipmap.disableGUIMipmaps);
		VRAMKillerConfigBase.Mipmap.disableFontMipmaps = getBool(values, "mipmap.disableFontMipmaps", VRAMKillerConfigBase.Mipmap.disableFontMipmaps);
		VRAMKillerConfigBase.Mipmap.particleMipmapLimit = getInt(values, "mipmap.particleMipmapLimit", VRAMKillerConfigBase.Mipmap.particleMipmapLimit, 0, 4);

		VRAMKillerConfigBase.Shadow.resolutionMin = getInt(values, "shadow.resolutionMin", VRAMKillerConfigBase.Shadow.resolutionMin, 256, 8192);
		VRAMKillerConfigBase.Shadow.resolutionMax = getInt(values, "shadow.resolutionMax", VRAMKillerConfigBase.Shadow.resolutionMax, 256, 8192);
		VRAMKillerConfigBase.Shadow.dynamicResolution = getBool(values, "shadow.dynamicResolution", VRAMKillerConfigBase.Shadow.dynamicResolution);

		VRAMKillerConfigBase.LeakDetection.enabled = getBool(values, "leakDetection.enabled", VRAMKillerConfigBase.LeakDetection.enabled);
		VRAMKillerConfigBase.LeakDetection.checkIntervalSeconds = getInt(values, "leakDetection.checkIntervalSeconds", VRAMKillerConfigBase.LeakDetection.checkIntervalSeconds, 60, 3600);
		VRAMKillerConfigBase.LeakDetection.maxOrphanReports = getInt(values, "leakDetection.maxOrphanReports", VRAMKillerConfigBase.LeakDetection.maxOrphanReports, 1, 100);
		VRAMKillerConfigBase.LeakDetection.autoCleanup = getBool(values, "leakDetection.autoCleanup", VRAMKillerConfigBase.LeakDetection.autoCleanup);

		VRAMKillerConfigBase.Animation.enabled = getBool(values, "animation.enabled", VRAMKillerConfigBase.Animation.enabled);
		VRAMKillerConfigBase.Animation.maxSkippedFrames = getInt(values, "animation.maxSkippedFrames", VRAMKillerConfigBase.Animation.maxSkippedFrames, 1, 30);
	}

	private static String generateTOML() {
		StringBuilder sb = new StringBuilder();
		sb.append("# VRAM Killer Configuration\n");
		sb.append("# Edit and save to reload automatically\n\n");

		appendSection(sb, "Display", () -> {
			appendSubSection(sb, "debugOverlay", () -> {
				appendComment(sb, "Show debug overlay on F3 screen");
				appendBool(sb, "enabled", VRAMKillerConfigBase.Display.DebugOverlay.enabled);
				appendComment(sb, "Show VRAM usage info");
				appendBool(sb, "showVRAMUsage", VRAMKillerConfigBase.Display.DebugOverlay.showVRAMUsage);
				appendComment(sb, "Show conversion statistics");
				appendBool(sb, "showConversionStats", VRAMKillerConfigBase.Display.DebugOverlay.showConversionStats);
				appendComment(sb, "Show texture statistics");
				appendBool(sb, "showTextureStats", VRAMKillerConfigBase.Display.DebugOverlay.showTextureStats);
				appendComment(sb, "Show leak detection info");
				appendBool(sb, "showLeakDetection", VRAMKillerConfigBase.Display.DebugOverlay.showLeakDetection);
				appendComment(sb, "Show atlas statistics");
				appendBool(sb, "showAtlasStats", VRAMKillerConfigBase.Display.DebugOverlay.showAtlasStats);
			});
		});

		appendSection(sb, "Conversion", () -> {
			appendComment(sb, "Enable RGBA5551 texture conversion (50% VRAM savings)");
			appendBool(sb, "enabled", VRAMKillerConfigBase.Conversion.enabled);
			appendComment(sb, "Enable RGB5_A1 format conversion for binary-alpha textures");
			appendBool(sb, "rgb5a1Conversion", VRAMKillerConfigBase.Conversion.rgb5a1Conversion);
			appendComment(sb, "Minimum texture dimension to convert (2-64)");
			appendInt(sb, "minTextureSize", VRAMKillerConfigBase.Conversion.minTextureSize);
			appendComment(sb, "Enable async processing where possible");
			appendBool(sb, "asyncProcessing", VRAMKillerConfigBase.Conversion.asyncProcessing);
		});

		appendSection(sb, "Cache", () -> {
			appendComment(sb, "Enable disk cache for converted textures");
			appendBool(sb, "enabled", VRAMKillerConfigBase.Cache.enabled);
			appendComment(sb, "Maximum cache size in MB (256-8192)");
			appendInt(sb, "maxSizeMB", VRAMKillerConfigBase.Cache.maxSizeMB);
			appendComment(sb, "Enable cold zone caching for distant textures");
			appendBool(sb, "enableColdZone", VRAMKillerConfigBase.Cache.enableColdZone);
		});

		appendSection(sb, "Scheduler", () -> {
			appendComment(sb, "Enable texture eviction scheduler");
			appendBool(sb, "enabled", VRAMKillerConfigBase.Scheduler.enabled);
			appendComment(sb, "Seconds before a texture moves to cold zone (10-300)");
			appendInt(sb, "coldZoneDelaySeconds", VRAMKillerConfigBase.Scheduler.coldZoneDelaySeconds);
			appendComment(sb, "VRAM usage percent threshold for eviction (50-99)");
			appendInt(sb, "maxVRAMUsagePercent", VRAMKillerConfigBase.Scheduler.maxVRAMUsagePercent);
		});

		appendSection(sb, "Mipmap", () -> {
			appendComment(sb, "Minimum mipmap level for 16x16 textures (0-4)");
			appendInt(sb, "minMipmapSize16x16", VRAMKillerConfigBase.Mipmap.minMipmapSize16x16);
			appendComment(sb, "Minimum mipmap level for HD textures (0-4)");
			appendInt(sb, "minMipmapSizeHD", VRAMKillerConfigBase.Mipmap.minMipmapSizeHD);
			appendComment(sb, "Disable mipmaps for GUI elements");
			appendBool(sb, "disableGUIMipmaps", VRAMKillerConfigBase.Mipmap.disableGUIMipmaps);
			appendComment(sb, "Disable mipmaps for font textures");
			appendBool(sb, "disableFontMipmaps", VRAMKillerConfigBase.Mipmap.disableFontMipmaps);
			appendComment(sb, "Mipmap limit for particle textures (0-4)");
			appendInt(sb, "particleMipmapLimit", VRAMKillerConfigBase.Mipmap.particleMipmapLimit);
		});

		appendSection(sb, "Shadow", () -> {
			appendComment(sb, "Minimum shadow resolution (256-8192)");
			appendInt(sb, "resolutionMin", VRAMKillerConfigBase.Shadow.resolutionMin);
			appendComment(sb, "Maximum shadow resolution (256-8192)");
			appendInt(sb, "resolutionMax", VRAMKillerConfigBase.Shadow.resolutionMax);
			appendComment(sb, "Dynamically adjust shadow resolution based on VRAM");
			appendBool(sb, "dynamicResolution", VRAMKillerConfigBase.Shadow.dynamicResolution);
		});

		appendSection(sb, "LeakDetection", () -> {
			appendComment(sb, "Enable texture leak detection");
			appendBool(sb, "enabled", VRAMKillerConfigBase.LeakDetection.enabled);
			appendComment(sb, "Check interval in seconds (60-3600)");
			appendInt(sb, "checkIntervalSeconds", VRAMKillerConfigBase.LeakDetection.checkIntervalSeconds);
			appendComment(sb, "Max orphan reports to show (1-100)");
			appendInt(sb, "maxOrphanReports", VRAMKillerConfigBase.LeakDetection.maxOrphanReports);
			appendComment(sb, "Auto-cleanup orphaned textures");
			appendBool(sb, "autoCleanup", VRAMKillerConfigBase.LeakDetection.autoCleanup);
		});

		appendSection(sb, "Animation", () -> {
			appendComment(sb, "Enable animation texture optimization");
			appendBool(sb, "enabled", VRAMKillerConfigBase.Animation.enabled);
			appendComment(sb, "Max frames to skip when invisible (1-30)");
			appendInt(sb, "maxSkippedFrames", VRAMKillerConfigBase.Animation.maxSkippedFrames);
		});

		return sb.toString();
	}

	private static boolean getBool(Map<String, String> map, String key, boolean fallback) {
		String v = map.get(key);
		if (v == null) return fallback;
		return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes");
	}

	private static int getInt(Map<String, String> map, String key, int fallback, int min, int max) {
		String v = map.get(key);
		if (v == null) return fallback;
		try {
			int val = Integer.parseInt(v.trim());
			return Math.clamp(val, min, max);
		} catch (NumberFormatException e) {
			VRAMKiller.LOGGER.warn("Invalid int value '{}' for '{}', using default: {}", v, key, fallback);
			return fallback;
		}
	}

	@SuppressWarnings("unchecked")
	private static <E extends Enum<E>> E getEnum(Map<String, String> map, String key, Class<E> type, E fallback) {
		String v = map.get(key);
		if (v == null) return fallback;
		try {
			return Enum.valueOf(type, v.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			VRAMKiller.LOGGER.warn("Invalid enum value '{}' for '{}', using default: {}", v, key, fallback);
			return fallback;
		}
	}

	private interface SectionAppender { void run(); }

	private static void appendSection(StringBuilder sb, String name, SectionAppender appender) {
		sb.append("[").append(name).append("]\n");
		appender.run();
		sb.append("\n");
	}

	private static void appendSubSection(StringBuilder sb, String name, SectionAppender appender) {
		sb.append("[").append(name).append("]\n");
		appender.run();
	}

	private static void appendComment(StringBuilder sb, String text) {
		sb.append("# ").append(text).append("\n");
	}

	private static void appendBool(StringBuilder sb, String key, boolean val) {
		sb.append(key).append(" = ").append(val).append("\n\n");
	}

	private static void appendInt(StringBuilder sb, String key, int val) {
		sb.append(key).append(" = ").append(val).append("\n\n");
	}

	private static void appendEnum(StringBuilder sb, String key, Enum<?> val) {
		sb.append(key).append(" = \"").append(val.name()).append("\"\n\n");
	}

	private VRAMConfig() {}
}
