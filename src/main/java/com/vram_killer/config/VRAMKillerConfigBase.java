package com.vram_killer.config;

public final class VRAMKillerConfigBase {

	public static final class Display {
		public static final class DebugOverlay {
			public static boolean enabled = true;
			public static boolean showVRAMUsage = true;
			public static boolean showConversionStats = true;
			public static boolean showTextureStats = true;
			public static boolean showLeakDetection = true;
			public static boolean showAtlasStats = true;

			private DebugOverlay() {}
		}
		public static final DebugOverlay debugOverlay = new DebugOverlay();

		private Display() {}
	}

	public static final class Conversion {
		public static boolean enabled = true;
		public static boolean rgb5a1Conversion = true;
		public static int minTextureSize = 4;
		public static boolean asyncProcessing = true;

		private Conversion() {}
	}

	public static final class Cache {
		public static boolean enabled = true;
		public static int maxSizeMB = 2048;
		public static boolean enableColdZone = true;

		private Cache() {}
	}

	public static final class Scheduler {
		public static boolean enabled = true;
		public static int coldZoneDelaySeconds = 30;
		public static int maxVRAMUsagePercent = 90;

		private Scheduler() {}
	}

	public static final class Mipmap {
		public static int minMipmapSize16x16 = 1;
		public static int minMipmapSizeHD = 0;
		public static boolean disableGUIMipmaps = true;
		public static boolean disableFontMipmaps = true;
		public static int particleMipmapLimit = 1;

		private Mipmap() {}
	}

	public static final class Shadow {
		public static int resolutionMin = 1024;
		public static int resolutionMax = 4096;
		public static boolean dynamicResolution = true;

		private Shadow() {}
	}

	public static final class LeakDetection {
		public static boolean enabled = true;
		public static int checkIntervalSeconds = 300;
		public static int maxOrphanReports = 10;
		public static boolean autoCleanup = false;

		private LeakDetection() {}
	}

	public static final class Animation {
		public static boolean enabled = true;
		public static int maxSkippedFrames = 5;

		private Animation() {}
	}

	static void validate() {
		Conversion.minTextureSize = Math.clamp(Conversion.minTextureSize, 2, 64);
		Cache.maxSizeMB = Math.clamp(Cache.maxSizeMB, 256, 8192);
		Scheduler.coldZoneDelaySeconds = Math.clamp(Scheduler.coldZoneDelaySeconds, 10, 300);
		Scheduler.maxVRAMUsagePercent = Math.clamp(Scheduler.maxVRAMUsagePercent, 50, 99);
		Mipmap.minMipmapSize16x16 = Math.clamp(Mipmap.minMipmapSize16x16, 0, 4);
		Mipmap.minMipmapSizeHD = Math.clamp(Mipmap.minMipmapSizeHD, 0, 4);
		Mipmap.particleMipmapLimit = Math.clamp(Mipmap.particleMipmapLimit, 0, 4);
		Shadow.resolutionMin = Math.clamp(Shadow.resolutionMin, 256, 8192);
		Shadow.resolutionMax = Math.clamp(Shadow.resolutionMax, 256, 8192);
		LeakDetection.checkIntervalSeconds = Math.clamp(LeakDetection.checkIntervalSeconds, 60, 3600);
		LeakDetection.maxOrphanReports = Math.clamp(LeakDetection.maxOrphanReports, 1, 100);
		Animation.maxSkippedFrames = Math.clamp(Animation.maxSkippedFrames, 1, 30);

		if (Shadow.resolutionMax < Shadow.resolutionMin) {
			int tmp = Shadow.resolutionMin;
			Shadow.resolutionMin = Shadow.resolutionMax;
			Shadow.resolutionMax = tmp;
		}
	}

	private VRAMKillerConfigBase() {}
}
