package com.vram_killer;

import com.vram_killer.config.VRAMConfig;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VRAMKiller implements ModInitializer {
	public static final String MOD_ID = "vram_killer";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static volatile Object vramManager;

	@Override
	public void onInitialize() {
		VRAMConfig.load();

		LOGGER.info("VRAM Killer initialized - Advanced VRAM optimization system ready");
		LOGGER.info("Conversion: {} | Cache: {} | Scheduler: {}", 
			com.vram_killer.config.VRAMKillerConfigBase.Conversion.enabled,
			com.vram_killer.config.VRAMKillerConfigBase.Cache.enabled,
			com.vram_killer.config.VRAMKillerConfigBase.Scheduler.enabled);
	}
	
	public static void setVRAMManager(Object manager) {
		vramManager = manager;
	}
	
	public static Object getVRAMManager() {
		return vramManager;
	}
}
