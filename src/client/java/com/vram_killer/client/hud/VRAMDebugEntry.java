package com.vram_killer.client.hud;

import com.vram_killer.VRAMKiller;
import com.vram_killer.client.VRAMKillerClient;
import com.vram_killer.config.VRAMKillerConfigBase;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

public class VRAMDebugEntry implements DebugScreenEntry {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(VRAMKiller.MOD_ID, "vram_stats");
    
    @Override
    public void display(DebugScreenDisplayer displayer, @Nullable Level serverOrClientLevel, 
                       @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
        if (!VRAMKillerConfigBase.Display.DebugOverlay.enabled) {
            return;
        }
        
        if (!VRAMKillerClient.isInitialized()) {
            return;
        }
        
        try {
            if (VRAMKillerConfigBase.Display.DebugOverlay.showVRAMUsage) {
                var monitor = VRAMKillerClient.getVRAMManager().getVRAMMonitor();
                if (monitor != null) {
                    double usagePercent = monitor.getCurrentVRAMUsagePercent();
                    String color = getColorForUsage(usagePercent);
                    
                    displayer.addLine("");
                    displayer.addLine(color + "[VRAM Killer]§r");
                    displayer.addLine(String.format("VRAM: %.1f%% (%d/%d MB)", 
                        usagePercent,
                        monitor.getVRAMUsageMB(),
                        monitor.getVRAMBudgetMB()));
                }
            }
            
            if (VRAMKillerConfigBase.Display.DebugOverlay.showConversionStats) {
                var interceptor = VRAMKillerClient.getUploadInterceptor();
                if (interceptor != null) {
                    displayer.addLine(String.format("RGB5A1: Converted=%d Skipped=%d Saved=%.1fMB",
                        interceptor.getConvertedCount(),
                        interceptor.getSkippedCount(),
                        interceptor.getSavedBytes() / (1024.0 * 1024.0)));
                }
            }
            
            if (VRAMKillerConfigBase.Display.DebugOverlay.showTextureStats) {
                var scheduler = VRAMKillerClient.getTextureScheduler();
                if (scheduler != null) {
                    displayer.addLine(String.format("Textures: Hot=%d Cold=%d (%.1f MB)", 
                        scheduler.getHotZoneSize(),
                        scheduler.getColdZoneSize(),
                        scheduler.getTotalEstimatedMemory() / (1024.0 * 1024.0)));
                }
            }
            
            if (VRAMKillerConfigBase.Display.DebugOverlay.showLeakDetection) {
                var leakDetector = VRAMKillerClient.getVRAMManager().getLeakDetector();
                if (leakDetector != null && leakDetector.getOrphanCount() > 0) {
                    displayer.addLine(String.format("§c[!] %d Orphaned Textures!§r", 
                        leakDetector.getOrphanCount()));
                }
            }
        } catch (Exception e) {
            VRAMKiller.LOGGER.debug("VRAM debug entry error: {}", e.getMessage());
        }
    }
    
    private String getColorForUsage(double percent) {
        if (percent < 50) return "§a";
        if (percent < 75) return "§e";
        if (percent < 90) return "§6";
        return "§c";
    }
}
