package com.vram_killer.client.mixin.client;

import com.vram_killer.client.VRAMKillerClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class GuiMixin {

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void onExtractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		try {
			if (!VRAMKillerClient.isInitialized()) return;

			var debugOverlay = VRAMKillerClient.getDebugOverlay();
			if (debugOverlay != null && debugOverlay.isPanelVisible()) {
				debugOverlay.renderDebugOverlay(graphics, deltaTracker.getGameTimeDeltaPartialTick(false));
			}
		} catch (Exception e) {
		}
	}
}
