package com.vram_killer.client;

import com.vram_killer.VRAMKiller;
import com.vram_killer.client.animation.AnimationTextureOptimizer;
import com.vram_killer.client.atlas.AtlasOptimizer;
import com.vram_killer.client.compatibility.CompatibilityChecker;
import com.vram_killer.client.compression.CompressionEngine;
import com.vram_killer.client.hud.DebugOverlayHandler;
import com.vram_killer.client.hud.VRAMDebugEntry;
import com.vram_killer.client.render.TextureUploadInterceptor;
import com.vram_killer.client.scheduler.TextureScheduler;
import com.vram_killer.client.shader.ShaderOptimizer;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VRAMKillerClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("VRAMKiller/Client");

	private static VRAMManager vramManager;
	private static TextureScheduler textureScheduler;
	private static CompressionEngine compressionEngine;
	private static TextureUploadInterceptor uploadInterceptor;
	private static ShaderOptimizer shaderOptimizer;
	private static AnimationTextureOptimizer animationOptimizer;
	private static AtlasOptimizer atlasOptimizer;
	private static DebugOverlayHandler debugOverlay;
	private static CompatibilityChecker compatibilityChecker;

	private static volatile boolean initialized = false;
	private static final Object initLock = new Object();

	@Override
	public void onInitializeClient() {
		DebugScreenEntries.register(VRAMDebugEntry.ID, new VRAMDebugEntry());
		LOGGER.info("VRAM Killer debug entry registered");

		try {
			var minecraft = Minecraft.getInstance();
			if (minecraft != null && minecraft.debugEntries != null) {
				minecraft.debugEntries.setStatus(VRAMDebugEntry.ID, DebugScreenEntryStatus.IN_OVERLAY);
				LOGGER.info("VRAM Killer debug entry set to IN_OVERLAY by default");
			}
		} catch (Exception e) {
			LOGGER.warn("Could not set default debug entry status: {}", e.getMessage());
		}

		LOGGER.info("VRAM Killer client entry point registered - Systems will initialize after OpenGL context is ready");
	}

	public static void ensureInitialized() {
		if (!initialized) {
			synchronized (initLock) {
				if (!initialized) {
					initializeSystems();
					initialized = true;
				}
			}
		}
	}

	private static void initializeSystems() {
		LOGGER.info("Initializing VRAM Killer systems...");

		compatibilityChecker = new CompatibilityChecker();
		compatibilityChecker.checkCompatibility();

		compressionEngine = new CompressionEngine();
		vramManager = new VRAMManager();
		textureScheduler = new TextureScheduler();
		uploadInterceptor = new TextureUploadInterceptor(compressionEngine);
		shaderOptimizer = new ShaderOptimizer();
		animationOptimizer = new AnimationTextureOptimizer();
		atlasOptimizer = new AtlasOptimizer();
		debugOverlay = new DebugOverlayHandler();

		com.vram_killer.VRAMKiller.setVRAMManager(vramManager);
		vramManager.initialize();
		textureScheduler.initialize();
		compressionEngine.initialize();
		uploadInterceptor.initialize();
		shaderOptimizer.initialize();
		animationOptimizer.initialize();
		debugOverlay.register();

		try {
			var minecraft = Minecraft.getInstance();
			if (minecraft != null && minecraft.debugEntries != null) {
				minecraft.debugEntries.setStatus(VRAMDebugEntry.ID, DebugScreenEntryStatus.IN_OVERLAY);
			}
		} catch (Exception e) {
			LOGGER.warn("Could not set debug entry status during initialization: {}", e.getMessage());
		}

		LOGGER.info("VRAM Killer client systems initialized");
	}

	public static VRAMManager getVRAMManager() {
		ensureInitialized();
		return vramManager;
	}

	public static TextureScheduler getTextureScheduler() {
		ensureInitialized();
		return textureScheduler;
	}

	public static TextureUploadInterceptor getUploadInterceptor() {
		ensureInitialized();
		return uploadInterceptor;
	}

	public static ShaderOptimizer getShaderOptimizer() {
		ensureInitialized();
		return shaderOptimizer;
	}

	public static AnimationTextureOptimizer getAnimationOptimizer() {
		ensureInitialized();
		return animationOptimizer;
	}

	public static AtlasOptimizer getAtlasOptimizer() {
		ensureInitialized();
		return atlasOptimizer;
	}

	public static DebugOverlayHandler getDebugOverlay() {
		ensureInitialized();
		return debugOverlay;
	}

	public static CompatibilityChecker getCompatibilityChecker() {
		ensureInitialized();
		return compatibilityChecker;
	}

	public static boolean isInitialized() {
		return initialized;
	}
}
