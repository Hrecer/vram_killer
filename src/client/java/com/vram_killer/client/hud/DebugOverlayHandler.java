package com.vram_killer.client.hud;

import com.vram_killer.VRAMKiller;
import com.vram_killer.client.VRAMKillerClient;
import com.vram_killer.client.atlas.AtlasOptimizer;
import com.vram_killer.client.atlas.SkylineBLPacker;
import com.vram_killer.client.leak.LeakDetector;
import com.vram_killer.client.monitor.VRAMMonitor;
import com.vram_killer.config.VRAMKillerConfigBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.List;

public class DebugOverlayHandler {
	private static final int PANEL_X = 10;
	private static final int PANEL_Y = 10;
	private static final int LINE_HEIGHT = 10;
	private static final int UPDATE_INTERVAL_FRAMES = 10;
	
	private volatile boolean showPanel;
	private boolean registered;
	private int frameCounter;
	private CachedPanelData cachedPanel;
	
	public DebugOverlayHandler() {
		this.showPanel = VRAMKillerConfigBase.Display.DebugOverlay.enabled;
		this.registered = false;
		this.frameCounter = 0;
		this.cachedPanel = new CachedPanelData();
	}

	public void register() {
		if (registered) return;
		
		registered = true;
		VRAMKiller.LOGGER.info("Debug overlay handler registered - Press F3+V to toggle VRAM panel");
	}

	public void renderDebugOverlay(GuiGraphicsExtractor context, float tickDelta) {
		if (!showPanel) return;
		
		if (!VRAMKillerConfigBase.Display.DebugOverlay.enabled) return;
		
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.getDebugOverlay().showDebugScreen()) {
			frameCounter++;
			
			if (frameCounter % UPDATE_INTERVAL_FRAMES == 0 || cachedPanel.lines[0] == null) {
				rebuildCache(minecraft);
			}
			
			renderCachedPanel(context, minecraft);
		}
	}

	private void rebuildCache(Minecraft minecraft) {
		int lineIndex = 0;
		
		VRAMMonitor monitor = VRAMKillerClient.getVRAMManager().getVRAMMonitor();
		if (monitor != null && VRAMKillerConfigBase.Display.DebugOverlay.showVRAMUsage) {
			double usagePercent = monitor.getCurrentVRAMUsagePercent();
			String color = getColorForUsage(usagePercent);
			
			cachedPanel.setLine(lineIndex++, "§e[VRAM Killer]§r Advanced Optimization Panel");
			cachedPanel.setLine(lineIndex++, "───────────────────────────────────");
			cachedPanel.setLine(lineIndex++, String.format("§7VRAM Usage: %s%.1f%%§r (%d/%d MB)", 
				color, usagePercent, monitor.getVRAMUsageMB(), monitor.getVRAMBudgetMB()));
			
			cachedPanel.usagePercent = usagePercent;
			cachedPanel.usageBarLine = lineIndex;
			lineIndex++;
			
			cachedPanel.setLine(lineIndex++, String.format("§7Avg Usage (60s): %.1f%%§r", 
				monitor.getAverageUsagePercent(60)));
		} else {
			lineIndex += 5;
		}
		
		cachedPanel.setLine(lineIndex++, "");
		cachedPanel.setLine(lineIndex++, "───────────────────────────────────");
		
		if (VRAMKillerConfigBase.Display.DebugOverlay.showCompressionStats) {
			var interceptor = VRAMKillerClient.getUploadInterceptor();
			if (interceptor != null) {
				cachedPanel.setLine(lineIndex++, String.format("§7Compression Ratio: %.2fx§r", 
					interceptor.getCompressionRatio()));
				cachedPanel.setLine(lineIndex++, String.format("§7Active Tasks: %d§r", 
					interceptor.getActiveTaskCount()));
			} else {
				lineIndex += 2;
			}
		}
		
		if (VRAMKillerConfigBase.Display.DebugOverlay.showTextureStats) {
			var scheduler = VRAMKillerClient.getTextureScheduler();
			if (scheduler != null) {
				cachedPanel.setLine(lineIndex++, String.format("§7Hot Zone: %d textures§r", 
					scheduler.getHotZoneSize()));
				cachedPanel.setLine(lineIndex++, String.format("§7Cold Zone: %d textures§r", 
					scheduler.getColdZoneSize()));
				cachedPanel.setLine(lineIndex++, String.format("§7Evicted: %d textures§r", 
					scheduler.getEvictedCount()));
				cachedPanel.setLine(lineIndex++, String.format("§7Est. Memory: %.2f MB§r", 
					scheduler.getTotalEstimatedMemory() / (1024.0 * 1024.0)));
			} else {
				lineIndex += 4;
			}
		}
		
		if (VRAMKillerConfigBase.Display.DebugOverlay.showLeakDetection) {
			cachedPanel.setLine(lineIndex++, "");
			cachedPanel.setLine(lineIndex++, "───────────────────────────────────");
			
			LeakDetector leakDetector = VRAMKillerClient.getVRAMManager().getLeakDetector();
			if (leakDetector != null) {
				int orphanCount = leakDetector.getOrphanCount();
				String leakStatus = orphanCount > 0 ? String.format("§c%d ORPHANS DETECTED!§r", orphanCount) : "§aNo leaks detected";
				cachedPanel.setLine(lineIndex++, String.format("§7Leaks: %s", leakStatus));
				cachedPanel.setLine(lineIndex++, String.format("§7Tracked: %d textures (%.2f MB)§r", 
					leakDetector.getTrackedCount(), 
					leakDetector.getTotalTrackedMemory() / (1024.0 * 1024.0)));
				
				List<LeakDetector.LeakReport> leaks = leakDetector.getPotentialLeaks();
				if (!leaks.isEmpty()) {
					cachedPanel.setLine(lineIndex++, "");
					cachedPanel.setLine(lineIndex++, "§cTop Suspects:");
					
					for (int i = 0; i < Math.min(3, leaks.size()); i++) {
						LeakDetector.LeakReport report = leaks.get(i);
						cachedPanel.setLine(lineIndex++, String.format(" §7* %s", report.formatReport()));
					}
				}
			} else {
				lineIndex += 3;
			}
		}
		
		if (VRAMKillerConfigBase.Display.DebugOverlay.showAtlasStats) {
			cachedPanel.setLine(lineIndex++, "");
			cachedPanel.setLine(lineIndex++, "───────────────────────────────────");
			
			AtlasOptimizer atlasOptimizer = VRAMKillerClient.getAtlasOptimizer();
			if (atlasOptimizer != null && atlasOptimizer.isEnabled()) {
				cachedPanel.setLine(lineIndex++, "§b[Atlas Optimization]§r Skyline-BL Algorithm");
				
				var atlasDataList = atlasOptimizer.getAllAtlasData();
				int totalAtlases = atlasDataList.size();
				long totalSpritesProcessed = 0;
				
				for (var data : atlasDataList) {
					totalSpritesProcessed += data.totalSpritesProcessed;
				}
				
				if (totalAtlases > 0) {
					cachedPanel.setLine(lineIndex++, String.format("§7Atlases: %d | Sprites: %d§r", 
						totalAtlases, totalSpritesProcessed));
					
					SkylineBLPacker.AtlasMetrics metrics = atlasOptimizer.getPacker().getMetrics();
					cachedPanel.setLine(lineIndex++, String.format("§7Utilization: %.1f%% | Wasted: %d px²", 
						metrics.utilizationPercent, metrics.wastedArea));
				} else {
					cachedPanel.setLine(lineIndex++, "§7No atlases optimized yet");
				}
			}
		}
		
		for (int i = lineIndex; i < cachedPanel.lines.length; i++) {
			cachedPanel.lines[i] = null;
		}
		
		cachedPanel.lastUpdateTime = System.currentTimeMillis();
	}

	private void renderCachedPanel(GuiGraphicsExtractor context, Minecraft minecraft) {
		Font font = minecraft.font;
		
		context.fill(PANEL_X - 2, PANEL_Y - 2, PANEL_X + 220, PANEL_Y + 240, 0xC0101010);
		
		int y = PANEL_Y;
		
		for (String line : cachedPanel.lines) {
			if (line == null) break;
			
			if (y - PANEL_Y >= cachedPanel.usageBarLine && y - PANEL_Y <= cachedPanel.usageBarLine + 1 && cachedPanel.usagePercent > 0) {
				renderUsageBar(context, PANEL_X, y, cachedPanel.usagePercent);
				y += LINE_HEIGHT + 4;
			} else {
				context.text(font, line, PANEL_X, y, 0xFFFFFFFF);
				y += LINE_HEIGHT;
			}
		}
	}

	private void renderUsageBar(GuiGraphicsExtractor context, int x, int y, double percent) {
		int barWidth = 200;
		int barHeight = 8;
		
		context.fill(x, y, x + barWidth, y + barHeight, 0xFF333333);
		
		int fillWidth = (int) (barWidth * Math.min(percent / 100.0, 1.0));
		int barColor = getColorIntForUsage(percent);
		
		if (fillWidth > 0) {
			context.fill(x, y, x + fillWidth, y + barHeight, barColor);
		}
		
		Minecraft minecraft = Minecraft.getInstance();
		String label = String.format("%.0f%%", percent);
		int textWidth = minecraft.font.width(label);
		context.text(minecraft.font, label, x + (barWidth - textWidth) / 2, y + 1, 0xFFFFFFFF);
	}

	private String getColorForUsage(double percent) {
		if (percent < 50) return "§a";
		if (percent < 75) return "§e";
		if (percent < 90) return "§6";
		return "§c";
	}

	private int getColorIntForUsage(double percent) {
		if (percent < 50) return 0xFF00AA00;
		if (percent < 75) return 0xFFAAAA00;
		if (percent < 90) return 0xFFCC6600;
		return 0xFFCC0000;
	}

	public void togglePanel() {
		showPanel = !showPanel;
		frameCounter = UPDATE_INTERVAL_FRAMES; 
		VRAMKiller.LOGGER.info("VRAM debug panel {}", showPanel ? "enabled" : "disabled");
	}

	public boolean isPanelVisible() {
		return showPanel && VRAMKillerConfigBase.Display.DebugOverlay.enabled;
	}
	
	public void setEnabled(boolean enabled) {
		this.showPanel = enabled;
	}
	
	private static class CachedPanelData {
		final String[] lines = new String[30];
		double usagePercent;
		int usageBarLine;
		long lastUpdateTime;
		
		void setLine(int index, String text) {
			if (index >= 0 && index < lines.length) {
				lines[index] = text;
			}
		}
	}
}
