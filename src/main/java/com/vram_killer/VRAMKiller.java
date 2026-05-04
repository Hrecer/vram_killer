package com.vram_killer;

import com.vram_killer.config.VRAMKillerConfigBase;
import com.vram_killer.config.config.VRAMKillerConfig;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VRAMKiller implements ModInitializer {
	public static final String MOD_ID = "vram_killer";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static VRAMKillerConfig config;
	private static volatile Object vramManager;

	@Override
	public void onInitialize() {
		config = VRAMKillerConfig.register(MOD_ID, VRAMKillerConfigBase.class);
		
		LOGGER.info("VRAM Killer initialized - Advanced VRAM optimization system ready");
		LOGGER.info("Compression: {} | Cache: {} | Scheduler: {}", 
			VRAMKillerConfigBase.Compression.enabled,
			VRAMKillerConfigBase.Cache.enabled,
			VRAMKillerConfigBase.Scheduler.enabled);
	}
	
	public static VRAMKillerConfig getConfig() {
		return config;
	}
	
	public static VRAMKillerConfigBase getConfigInstance() {
		return config != null ? config.get() : null;
	}
	
	public static void setVRAMManager(Object manager) {
		vramManager = manager;
	}
	
	public static Object getVRAMManager() {
		return vramManager;
	}
}
