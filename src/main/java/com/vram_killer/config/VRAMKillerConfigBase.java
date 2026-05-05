package com.vram_killer.config;

import com.vram_killer.config.annotation.ClientOnly;
import com.vram_killer.config.annotation.ConfigVersion;
import com.vram_killer.config.annotation.Range;
import com.vram_killer.config.annotation.SubCategory;

@ConfigVersion(1)
public class VRAMKillerConfigBase {

    @ClientOnly
    @SubCategory("Display")
    public Display display = new Display();

    @ClientOnly
    @SubCategory("Compression")
    public Compression compression = new Compression();

    @ClientOnly
    @SubCategory("Cache")
    public Cache cache = new Cache();

    @ClientOnly
    @SubCategory("Scheduler")
    public Scheduler scheduler = new Scheduler();

    @ClientOnly
    @SubCategory("Mipmap")
    public Mipmap mipmap = new Mipmap();

    @ClientOnly
    @SubCategory("Shadow")
    public Shadow shadow = new Shadow();

    @ClientOnly
    @SubCategory("LeakDetection")
    public LeakDetection leakDetection = new LeakDetection();

    @ClientOnly
    @SubCategory("Animation")
    public Animation animation = new Animation();

    public void syncToStaticFields() {
        Display.DebugOverlay.enabled = display.debugOverlay.i_enabled;
        Display.DebugOverlay.showVRAMUsage = display.debugOverlay.i_showVRAMUsage;
        Display.DebugOverlay.showCompressionStats = display.debugOverlay.i_showCompressionStats;
        Display.DebugOverlay.showTextureStats = display.debugOverlay.i_showTextureStats;
        Display.DebugOverlay.showLeakDetection = display.debugOverlay.i_showLeakDetection;
        Display.DebugOverlay.showAtlasStats = display.debugOverlay.i_showAtlasStats;

        Compression.enabled = compression.i_enabled;
        Compression.colorFormat = compression.i_colorFormat;
        Compression.normalFormat = compression.i_normalFormat;
        Compression.backgroundFormat = compression.i_backgroundFormat;
        Compression.threadCount = compression.i_threadCount;
        Compression.asyncCompression = compression.i_asyncCompression;

        Cache.enabled = cache.i_enabled;
        Cache.maxSizeMB = cache.i_maxSizeMB;
        Cache.enableColdZone = cache.i_enableColdZone;

        Scheduler.enabled = scheduler.i_enabled;
        Scheduler.coldZoneDelaySeconds = scheduler.i_coldZoneDelaySeconds;
        Scheduler.maxVRAMUsagePercent = scheduler.i_maxVRAMUsagePercent;

        Mipmap.minMipmapSize16x16 = mipmap.i_minMipmapSize16x16;
        Mipmap.minMipmapSizeHD = mipmap.i_minMipmapSizeHD;
        Mipmap.disableGUIMipmaps = mipmap.i_disableGUIMipmaps;
        Mipmap.disableFontMipmaps = mipmap.i_disableFontMipmaps;
        Mipmap.particleMipmapLimit = mipmap.i_particleMipmapLimit;

        Shadow.resolutionMin = shadow.i_resolutionMin;
        Shadow.resolutionMax = shadow.i_resolutionMax;
        Shadow.dynamicResolution = shadow.i_dynamicResolution;

        LeakDetection.enabled = leakDetection.i_enabled;
        LeakDetection.checkIntervalSeconds = leakDetection.i_checkIntervalSeconds;
        LeakDetection.maxOrphanReports = leakDetection.i_maxOrphanReports;
        LeakDetection.autoCleanup = leakDetection.i_autoCleanup;

        Animation.enabled = animation.i_enabled;
        Animation.maxSkippedFrames = animation.i_maxSkippedFrames;
    }

    @ClientOnly
    public static class Display {
        @SubCategory("DebugOverlay")
        public DebugOverlay debugOverlay = new DebugOverlay();

        public static class DebugOverlay {
            public boolean i_enabled = true;
            public boolean i_showVRAMUsage = true;
            public boolean i_showCompressionStats = true;
            public boolean i_showTextureStats = true;
            public boolean i_showLeakDetection = true;
            public boolean i_showAtlasStats = true;

            public static boolean enabled = true;
            public static boolean showVRAMUsage = true;
            public static boolean showCompressionStats = true;
            public static boolean showTextureStats = true;
            public static boolean showLeakDetection = true;
            public static boolean showAtlasStats = true;
        }
    }

    @ClientOnly
    public static class Compression {
        public boolean i_enabled = true;
        public CompressionFormat i_colorFormat = CompressionFormat.BC7;
        public CompressionFormat i_normalFormat = CompressionFormat.BC5;
        public CompressionFormat i_backgroundFormat = CompressionFormat.BC1;
        @Range(min = 1, max = 16)
        public int i_threadCount = 4;
        public boolean i_asyncCompression = true;

        public static boolean enabled = true;
        public static CompressionFormat colorFormat = CompressionFormat.BC7;
        public static CompressionFormat normalFormat = CompressionFormat.BC5;
        public static CompressionFormat backgroundFormat = CompressionFormat.BC1;
        public static int threadCount = 4;
        public static boolean asyncCompression = true;

        public enum CompressionFormat {
            BC1(4, 0.25f, "Fastest, 6:1 compression"),
            BC3(8, 0.5f, "Alpha support, 4:1 compression"),
            BC5(8, 0.5f, "Normal maps, 4:1 compression"),
            BC7(8, 0.5f, "Best quality, 4:1 compression");

            public final int bytesPerPixel;
            public final float compressionRatio;
            public final String description;

            CompressionFormat(int bytesPerPixel, float compressionRatio, String description) {
                this.bytesPerPixel = bytesPerPixel;
                this.compressionRatio = compressionRatio;
                this.description = description;
            }
        }
    }

    @ClientOnly
    public static class Cache {
        public boolean i_enabled = true;
        @Range(min = 256, max = 8192)
        public int i_maxSizeMB = 2048;
        public boolean i_enableColdZone = true;

        public static boolean enabled = true;
        public static int maxSizeMB = 2048;
        public static boolean enableColdZone = true;
    }

    @ClientOnly
    public static class Scheduler {
        public boolean i_enabled = true;
        @Range(min = 10, max = 300)
        public int i_coldZoneDelaySeconds = 30;
        @Range(min = 50, max = 99)
        public int i_maxVRAMUsagePercent = 90;

        public static boolean enabled = true;
        public static int coldZoneDelaySeconds = 30;
        public static int maxVRAMUsagePercent = 90;
    }

    @ClientOnly
    public static class Mipmap {
        @Range(min = 0, max = 4)
        public int i_minMipmapSize16x16 = 1;
        @Range(min = 0, max = 4)
        public int i_minMipmapSizeHD = 0;
        public boolean i_disableGUIMipmaps = true;
        public boolean i_disableFontMipmaps = true;
        @Range(min = 0, max = 4)
        public int i_particleMipmapLimit = 1;

        public static int minMipmapSize16x16 = 1;
        public static int minMipmapSizeHD = 0;
        public static boolean disableGUIMipmaps = true;
        public static boolean disableFontMipmaps = true;
        public static int particleMipmapLimit = 1;
    }

    @ClientOnly
    public static class Shadow {
        @Range(min = 256, max = 8192)
        public int i_resolutionMin = 1024;
        @Range(min = 256, max = 8192)
        public int i_resolutionMax = 4096;
        public boolean i_dynamicResolution = true;

        public static int resolutionMin = 1024;
        public static int resolutionMax = 4096;
        public static boolean dynamicResolution = true;
    }

    @ClientOnly
    public static class LeakDetection {
        public boolean i_enabled = true;
        @Range(min = 60, max = 3600)
        public int i_checkIntervalSeconds = 300;
        @Range(min = 1, max = 100)
        public int i_maxOrphanReports = 10;
        public boolean i_autoCleanup = false;

        public static boolean enabled = true;
        public static int checkIntervalSeconds = 300;
        public static int maxOrphanReports = 10;
        public static boolean autoCleanup = false;
    }

    @ClientOnly
    public static class Animation {
        public boolean i_enabled = true;
        @Range(min = 1, max = 30)
        public int i_maxSkippedFrames = 5;

        public static boolean enabled = true;
        public static int maxSkippedFrames = 5;
    }
}
