package com.vram_killer.client.compatibility;

import com.vram_killer.VRAMKiller;
import com.vram_killer.config.VRAMKillerConfigBase;

import java.lang.reflect.Method;

public class SodiumIntegrationBridge {
	private static boolean sodiumDetected = false;
	private static boolean sodiumMemoryTrackingAvailable = false;
	private static Object sodiumMemoryTracker = null;

	public static void detectAndInitialize() {
		detectSodium();
		if (sodiumDetected) {
			initializeSodiumIntegration();
		}
	}

	private static void detectSodium() {
		String[] sodiumClasses = {
			"net.caffeinemc.mods.sodium.client.SodiumClientMod",
			"me.jellysquid.mods.sodium.client.SodiumClientMod",
			"com.jellysquid.mods.sodium.client.SodiumClientMod",
			"net.caffeinemc.mods.sodium.client.gl.device.RenderDevice"
		};

		for (String className : sodiumClasses) {
			try {
				Class.forName(className);
				sodiumDetected = true;
				VRAMKiller.LOGGER.info("Sodium detected via: {}", className);
				return;
			} catch (ClassNotFoundException ignored) {}
		}

		VRAMKiller.LOGGER.info("Sodium not detected - using vanilla memory tracking");
	}

	private static void initializeSodiumIntegration() {
		String[] trackerClasses = {
			"net.caffeinemc.mods.sodium.client.util.memory.MemoryUtil",
			"me.jellysquid.mods.sodium.client.util.memory.MemoryUtil",
			"net.caffeinemc.mods.sodium.client.render.texture.ArenaAllocator",
			"me.jellysquid.mods.sodium.client.render.texture.ArenaAllocator"
		};

		for (String className : trackerClasses) {
			try {
				Class<?> trackerClass = Class.forName(className);
				Method getInstance = null;
				for (Method m : trackerClass.getMethods()) {
					if (m.getName().equals("getInstance") && m.getParameterCount() == 0) {
						getInstance = m;
						break;
					}
				}
				if (getInstance != null) {
					sodiumMemoryTracker = getInstance.invoke(null);
					sodiumMemoryTrackingAvailable = true;
					VRAMKiller.LOGGER.info("Sodium memory tracking connected via: {}", className);
					return;
				}
			} catch (Exception ignored) {}
		}

		VRAMKiller.LOGGER.info("Sodium detected but memory tracking API not available - using fallback");
	}

	public static boolean isSodiumDetected() {
		return sodiumDetected;
	}

	public static long getSodiumMemoryUsage() {
		if (!sodiumMemoryTrackingAvailable || sodiumMemoryTracker == null) return -1;

		try {
			for (Method m : sodiumMemoryTracker.getClass().getMethods()) {
				if (m.getName().equals("getAllocatedMemory") && m.getParameterCount() == 0) {
					Object result = m.invoke(sodiumMemoryTracker);
					if (result instanceof Long) return (Long) result;
					if (result instanceof Integer) return ((Integer) result).longValue();
				}
			}
		} catch (Exception ignored) {}

		return -1;
	}

	public static boolean isMemoryTrackingAvailable() {
		return sodiumMemoryTrackingAvailable;
	}
}
