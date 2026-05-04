package com.vram_killer.client.integration;

import com.vram_killer.VRAMKiller;
import com.vram_killer.config.VRAMKillerConfigBase;
import com.vram_killer.config.config.VRAMKillerConfig;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlValueFormatterImpls;
import net.caffeinemc.mods.sodium.api.config.structure.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class VRAMKillerSodiumEntryPoint implements ConfigEntryPoint {

    private static final StorageEventHandler STORAGE_HANDLER = () -> {
        VRAMKillerConfig config = VRAMKillerConfig.getConfig(VRAMKiller.MOD_ID);
        if (config != null) {
            config.save();
        }
    };

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        ModOptionsBuilder modOptions = builder.registerModOptions(VRAMKiller.MOD_ID, "VRAM Killer", "1.0.0");

        modOptions.addPage(buildDisplayPage(builder));
        modOptions.addPage(buildCompressionPage(builder));
        modOptions.addPage(buildCachePage(builder));
        modOptions.addPage(buildSchedulerPage(builder));
        modOptions.addPage(buildMipmapPage(builder));
        modOptions.addPage(buildShadowPage(builder));
        modOptions.addPage(buildLeakDetectionPage(builder));
    }

    private OptionPageBuilder buildDisplayPage(ConfigBuilder builder) {
        OptionPageBuilder page = builder.createOptionPage();
        page.setName(Component.translatable("config.vram_killer.category.display"));

        OptionGroupBuilder debugOverlayGroup = builder.createOptionGroup();
        debugOverlayGroup.setName(Component.translatable("config.vram_killer.display.debugOverlay"));
        
        debugOverlayGroup.addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "debug_overlay_enabled"))
                .setName(Component.translatable("config.vram_killer.display.debugOverlay_enabled"))
                .setTooltip(Component.translatable("config.vram_killer.display.debugOverlay_enabled.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Display.DebugOverlay.enabled)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Display.DebugOverlay.enabled = val,
                        () -> VRAMKillerConfigBase.Display.DebugOverlay.enabled));
        
        debugOverlayGroup.addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "show_vram_usage"))
                .setName(Component.translatable("config.vram_killer.display.debugOverlay_showVRAMUsage"))
                .setTooltip(Component.translatable("config.vram_killer.display.debugOverlay_showVRAMUsage.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Display.DebugOverlay.showVRAMUsage)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Display.DebugOverlay.showVRAMUsage = val,
                        () -> VRAMKillerConfigBase.Display.DebugOverlay.showVRAMUsage));
        
        debugOverlayGroup.addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "show_compression_stats"))
                .setName(Component.translatable("config.vram_killer.display.debugOverlay_showCompressionStats"))
                .setTooltip(Component.translatable("config.vram_killer.display.debugOverlay_showCompressionStats.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Display.DebugOverlay.showCompressionStats)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Display.DebugOverlay.showCompressionStats = val,
                        () -> VRAMKillerConfigBase.Display.DebugOverlay.showCompressionStats));
        
        debugOverlayGroup.addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "show_texture_stats"))
                .setName(Component.translatable("config.vram_killer.display.debugOverlay_showTextureStats"))
                .setTooltip(Component.translatable("config.vram_killer.display.debugOverlay_showTextureStats.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Display.DebugOverlay.showTextureStats)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Display.DebugOverlay.showTextureStats = val,
                        () -> VRAMKillerConfigBase.Display.DebugOverlay.showTextureStats));
        
        debugOverlayGroup.addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "show_leak_detection"))
                .setName(Component.translatable("config.vram_killer.display.debugOverlay_showLeakDetection"))
                .setTooltip(Component.translatable("config.vram_killer.display.debugOverlay_showLeakDetection.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Display.DebugOverlay.showLeakDetection)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Display.DebugOverlay.showLeakDetection = val,
                        () -> VRAMKillerConfigBase.Display.DebugOverlay.showLeakDetection));
        
        debugOverlayGroup.addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "show_atlas_stats"))
                .setName(Component.translatable("config.vram_killer.display.debugOverlay_showAtlasStats"))
                .setTooltip(Component.translatable("config.vram_killer.display.debugOverlay_showAtlasStats.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Display.DebugOverlay.showAtlasStats)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Display.DebugOverlay.showAtlasStats = val,
                        () -> VRAMKillerConfigBase.Display.DebugOverlay.showAtlasStats));
        
        page.addOptionGroup(debugOverlayGroup);

        return page;
    }

    private OptionPageBuilder buildCompressionPage(ConfigBuilder builder) {
        OptionPageBuilder page = builder.createOptionPage();
        page.setName(Component.translatable("config.vram_killer.category.compression"));

        OptionGroupBuilder mainGroup = builder.createOptionGroup();
        mainGroup.setName(Component.translatable("config.vram_killer.compression.main"));
        
        mainGroup.addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "compression_enabled"))
                .setName(Component.translatable("config.vram_killer.compression_enabled"))
                .setTooltip(Component.translatable("config.vram_killer.compression_enabled.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Compression.enabled)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Compression.enabled = val,
                        () -> VRAMKillerConfigBase.Compression.enabled));
        
        mainGroup.addOption(builder.createEnumOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "compression_color_format"),
                        VRAMKillerConfigBase.Compression.CompressionFormat.class)
                .setName(Component.translatable("config.vram_killer.compression_colorFormat"))
                .setTooltip(Component.translatable("config.vram_killer.compression_colorFormat.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Compression.colorFormat)
                .setElementNameProvider(e -> Component.translatable("config.vram_killer.compression.format." + e.name().toLowerCase()))
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Compression.colorFormat = val,
                        () -> VRAMKillerConfigBase.Compression.colorFormat));
        
        mainGroup.addOption(builder.createEnumOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "compression_normal_format"),
                        VRAMKillerConfigBase.Compression.CompressionFormat.class)
                .setName(Component.translatable("config.vram_killer.compression_normalFormat"))
                .setTooltip(Component.translatable("config.vram_killer.compression_normalFormat.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Compression.normalFormat)
                .setElementNameProvider(e -> Component.translatable("config.vram_killer.compression.format." + e.name().toLowerCase()))
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Compression.normalFormat = val,
                        () -> VRAMKillerConfigBase.Compression.normalFormat));
        
        mainGroup.addOption(builder.createEnumOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "compression_background_format"),
                        VRAMKillerConfigBase.Compression.CompressionFormat.class)
                .setName(Component.translatable("config.vram_killer.compression_backgroundFormat"))
                .setTooltip(Component.translatable("config.vram_killer.compression_backgroundFormat.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Compression.backgroundFormat)
                .setElementNameProvider(e -> Component.translatable("config.vram_killer.compression.format." + e.name().toLowerCase()))
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Compression.backgroundFormat = val,
                        () -> VRAMKillerConfigBase.Compression.backgroundFormat));
        
        mainGroup.addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "compression_thread_count"))
                .setName(Component.translatable("config.vram_killer.compression_threadCount"))
                .setTooltip(Component.translatable("config.vram_killer.compression_threadCount.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Compression.threadCount)
                .setValueFormatter(ControlValueFormatterImpls.number()).setRange(1, 16, 1)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Compression.threadCount = val,
                        () -> VRAMKillerConfigBase.Compression.threadCount));
        
        page.addOptionGroup(mainGroup);

        return page;
    }

    private OptionPageBuilder buildCachePage(ConfigBuilder builder) {
        OptionPageBuilder page = builder.createOptionPage();
        page.setName(Component.translatable("config.vram_killer.category.cache"));

        OptionGroupBuilder group = builder.createOptionGroup();
        group.setName(Component.translatable("config.vram_killer.category.cache"));
        
        group.addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "cache_enabled"))
                .setName(Component.translatable("config.vram_killer.cache_enabled"))
                .setTooltip(Component.translatable("config.vram_killer.cache_enabled.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Cache.enabled)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Cache.enabled = val,
                        () -> VRAMKillerConfigBase.Cache.enabled));
        
        group.addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "cache_max_size"))
                .setName(Component.translatable("config.vram_killer.cache_maxSizeMB"))
                .setTooltip(Component.translatable("config.vram_killer.cache_maxSizeMB.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Cache.maxSizeMB)
                .setValueFormatter(ControlValueFormatterImpls.number()).setRange(256, 8192, 256)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Cache.maxSizeMB = val,
                        () -> VRAMKillerConfigBase.Cache.maxSizeMB));
        
        group.addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "cache_enable_cold_zone"))
                .setName(Component.translatable("config.vram_killer.cache_enableColdZone"))
                .setTooltip(Component.translatable("config.vram_killer.cache_enableColdZone.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Cache.enableColdZone)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Cache.enableColdZone = val,
                        () -> VRAMKillerConfigBase.Cache.enableColdZone));
        
        page.addOptionGroup(group);

        return page;
    }

    private OptionPageBuilder buildSchedulerPage(ConfigBuilder builder) {
        OptionPageBuilder page = builder.createOptionPage();
        page.setName(Component.translatable("config.vram_killer.category.scheduler"));

        OptionGroupBuilder group = builder.createOptionGroup();
        group.setName(Component.translatable("config.vram_killer.category.scheduler"));
        
        group.addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "scheduler_enabled"))
                .setName(Component.translatable("config.vram_killer.scheduler_enabled"))
                .setTooltip(Component.translatable("config.vram_killer.scheduler_enabled.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Scheduler.enabled)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Scheduler.enabled = val,
                        () -> VRAMKillerConfigBase.Scheduler.enabled));
        
        group.addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "scheduler_cold_zone_delay"))
                .setName(Component.translatable("config.vram_killer.scheduler_coldZoneDelaySeconds"))
                .setTooltip(Component.translatable("config.vram_killer.scheduler_coldZoneDelaySeconds.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Scheduler.coldZoneDelaySeconds)
                .setValueFormatter(ControlValueFormatterImpls.number()).setRange(10, 300, 10)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Scheduler.coldZoneDelaySeconds = val,
                        () -> VRAMKillerConfigBase.Scheduler.coldZoneDelaySeconds));
        
        group.addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "scheduler_max_vram_usage"))
                .setName(Component.translatable("config.vram_killer.scheduler_maxVRAMUsagePercent"))
                .setTooltip(Component.translatable("config.vram_killer.scheduler_maxVRAMUsagePercent.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Scheduler.maxVRAMUsagePercent)
                .setValueFormatter(ControlValueFormatterImpls.number()).setRange(50, 99, 1)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Scheduler.maxVRAMUsagePercent = val,
                        () -> VRAMKillerConfigBase.Scheduler.maxVRAMUsagePercent));
        
        page.addOptionGroup(group);

        return page;
    }

    private OptionPageBuilder buildMipmapPage(ConfigBuilder builder) {
        OptionPageBuilder page = builder.createOptionPage();
        page.setName(Component.translatable("config.vram_killer.category.mipmap"));

        OptionGroupBuilder group = builder.createOptionGroup();
        group.setName(Component.translatable("config.vram_killer.category.mipmap"));
        
        group.addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "mipmap_min_size_16x16"))
                .setName(Component.translatable("config.vram_killer.mipmap_minMipmapSize16x16"))
                .setTooltip(Component.translatable("config.vram_killer.mipmap_minMipmapSize16x16.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Mipmap.minMipmapSize16x16)
                .setValueFormatter(ControlValueFormatterImpls.number()).setRange(0, 4, 1)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Mipmap.minMipmapSize16x16 = val,
                        () -> VRAMKillerConfigBase.Mipmap.minMipmapSize16x16));
        
        group.addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "mipmap_min_size_hd"))
                .setName(Component.translatable("config.vram_killer.mipmap_minMipmapSizeHD"))
                .setTooltip(Component.translatable("config.vram_killer.mipmap_minMipmapSizeHD.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Mipmap.minMipmapSizeHD)
                .setValueFormatter(ControlValueFormatterImpls.number()).setRange(0, 4, 1)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Mipmap.minMipmapSizeHD = val,
                        () -> VRAMKillerConfigBase.Mipmap.minMipmapSizeHD));
        
        group.addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "mipmap_disable_gui"))
                .setName(Component.translatable("config.vram_killer.mipmap_disableGUIMipmaps"))
                .setTooltip(Component.translatable("config.vram_killer.mipmap_disableGUIMipmaps.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Mipmap.disableGUIMipmaps)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Mipmap.disableGUIMipmaps = val,
                        () -> VRAMKillerConfigBase.Mipmap.disableGUIMipmaps));
        
        group.addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "mipmap_disable_font"))
                .setName(Component.translatable("config.vram_killer.mipmap_disableFontMipmaps"))
                .setTooltip(Component.translatable("config.vram_killer.mipmap_disableFontMipmaps.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Mipmap.disableFontMipmaps)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Mipmap.disableFontMipmaps = val,
                        () -> VRAMKillerConfigBase.Mipmap.disableFontMipmaps));
        
        group.addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "mipmap_particle_limit"))
                .setName(Component.translatable("config.vram_killer.mipmap_particleMipmapLimit"))
                .setTooltip(Component.translatable("config.vram_killer.mipmap_particleMipmapLimit.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Mipmap.particleMipmapLimit)
                .setValueFormatter(ControlValueFormatterImpls.number()).setRange(0, 4, 1)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Mipmap.particleMipmapLimit = val,
                        () -> VRAMKillerConfigBase.Mipmap.particleMipmapLimit));
        
        page.addOptionGroup(group);

        return page;
    }

    private OptionPageBuilder buildShadowPage(ConfigBuilder builder) {
        OptionPageBuilder page = builder.createOptionPage();
        page.setName(Component.translatable("config.vram_killer.category.shadow"));

        OptionGroupBuilder group = builder.createOptionGroup();
        group.setName(Component.translatable("config.vram_killer.category.shadow"));
        
        group.addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "shadow_resolution_min"))
                .setName(Component.translatable("config.vram_killer.shadow_resolutionMin"))
                .setTooltip(Component.translatable("config.vram_killer.shadow_resolutionMin.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Shadow.resolutionMin)
                .setValueFormatter(ControlValueFormatterImpls.number()).setRange(256, 8192, 256)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Shadow.resolutionMin = val,
                        () -> VRAMKillerConfigBase.Shadow.resolutionMin));
        
        group.addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "shadow_resolution_max"))
                .setName(Component.translatable("config.vram_killer.shadow_resolutionMax"))
                .setTooltip(Component.translatable("config.vram_killer.shadow_resolutionMax.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Shadow.resolutionMax)
                .setValueFormatter(ControlValueFormatterImpls.number()).setRange(256, 8192, 256)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Shadow.resolutionMax = val,
                        () -> VRAMKillerConfigBase.Shadow.resolutionMax));
        
        group.addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "shadow_dynamic_resolution"))
                .setName(Component.translatable("config.vram_killer.shadow_dynamicResolution"))
                .setTooltip(Component.translatable("config.vram_killer.shadow_dynamicResolution.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.Shadow.dynamicResolution)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.Shadow.dynamicResolution = val,
                        () -> VRAMKillerConfigBase.Shadow.dynamicResolution));
        
        page.addOptionGroup(group);

        return page;
    }

    private OptionPageBuilder buildLeakDetectionPage(ConfigBuilder builder) {
        OptionPageBuilder page = builder.createOptionPage();
        page.setName(Component.translatable("config.vram_killer.category.leakDetection"));

        OptionGroupBuilder group = builder.createOptionGroup();
        group.setName(Component.translatable("config.vram_killer.category.leakDetection"));
        
        group.addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "leak_detection_enabled"))
                .setName(Component.translatable("config.vram_killer.leakDetection_enabled"))
                .setTooltip(Component.translatable("config.vram_killer.leakDetection_enabled.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.LeakDetection.enabled)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.LeakDetection.enabled = val,
                        () -> VRAMKillerConfigBase.LeakDetection.enabled));
        
        group.addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "leak_detection_check_interval"))
                .setName(Component.translatable("config.vram_killer.leakDetection_checkIntervalSeconds"))
                .setTooltip(Component.translatable("config.vram_killer.leakDetection_checkIntervalSeconds.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.LeakDetection.checkIntervalSeconds)
                .setValueFormatter(ControlValueFormatterImpls.number()).setRange(60, 3600, 60)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.LeakDetection.checkIntervalSeconds = val,
                        () -> VRAMKillerConfigBase.LeakDetection.checkIntervalSeconds));
        
        group.addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "leak_detection_max_reports"))
                .setName(Component.translatable("config.vram_killer.leakDetection_maxOrphanReports"))
                .setTooltip(Component.translatable("config.vram_killer.leakDetection_maxOrphanReports.tooltip"))
                .setDefaultValue(VRAMKillerConfigBase.LeakDetection.maxOrphanReports)
                .setValueFormatter(ControlValueFormatterImpls.number()).setRange(1, 100, 1)
                .setStorageHandler(STORAGE_HANDLER).setBinding(val -> VRAMKillerConfigBase.LeakDetection.maxOrphanReports = val,
                        () -> VRAMKillerConfigBase.LeakDetection.maxOrphanReports));
        
        page.addOptionGroup(group);

        return page;
    }
}
