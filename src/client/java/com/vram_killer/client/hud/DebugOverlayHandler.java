package com.vram_killer.client.hud;

import com.vram_killer.VRAMKiller;
import com.vram_killer.config.VRAMKillerConfigBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;

public class DebugOverlayHandler {
	private volatile boolean showPanel;
	private boolean registered;

	public DebugOverlayHandler() {
		this.showPanel = VRAMKillerConfigBase.Display.DebugOverlay.enabled;
		this.registered = false;
	}

	public void register() {
		if (registered) return;

		registered = true;
		VRAMKiller.LOGGER.info("Debug overlay handler registered - Press F3+V to toggle VRAM stats in F3 screen");
	}

	public void togglePanel() {
		showPanel = !showPanel;
		VRAMKiller.LOGGER.info("VRAM debug stats {}", showPanel ? "enabled" : "disabled");

		try {
			var minecraft = Minecraft.getInstance();
			if (minecraft != null && minecraft.debugEntries != null) {
				var status = showPanel ? DebugScreenEntryStatus.IN_OVERLAY : DebugScreenEntryStatus.NEVER;
				minecraft.debugEntries.setStatus(VRAMDebugEntry.ID, status);
			}
		} catch (Exception e) {
			VRAMKiller.LOGGER.debug("Could not update debug entry status: {}", e.getMessage());
		}
	}

	public boolean isPanelVisible() {
		return showPanel && VRAMKillerConfigBase.Display.DebugOverlay.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.showPanel = enabled;
	}
}
