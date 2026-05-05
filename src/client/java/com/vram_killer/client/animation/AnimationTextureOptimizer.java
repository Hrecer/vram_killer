package com.vram_killer.client.animation;

import com.vram_killer.VRAMKiller;
import com.vram_killer.config.VRAMKillerConfigBase;
import net.minecraft.client.renderer.texture.SpriteContents;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AnimationTextureOptimizer {
	private final ConcurrentHashMap<Integer, AnimationTexture> animationTextures;
	private final ConcurrentHashMap<Integer, Boolean> visibilityState;
	private final ConcurrentLinkedQueue<PendingFrameUpdate> pendingUpdates;
	private volatile boolean enabled;

	public AnimationTextureOptimizer() {
		this.animationTextures = new ConcurrentHashMap<>();
		this.visibilityState = new ConcurrentHashMap<>();
		this.pendingUpdates = new ConcurrentLinkedQueue<>();
		this.enabled = false;
	}

	public void initialize() {
		this.enabled = VRAMKillerConfigBase.Animation.enabled;
		if (enabled) {
			VRAMKiller.LOGGER.info("Animation texture optimizer initialized (visibility-based frame skipping)");
		}
	}

	public boolean isEnabled() { return enabled; }

	public void registerAnimationTexture(int textureId, SpriteContents sprite,
										  int width, int height) {
		if (!enabled || textureId <= 0) return;

		AnimationTexture animTex = new AnimationTexture(textureId, sprite, width, height);
		animationTextures.put(textureId, animTex);
		visibilityState.put(textureId, true);
		VRAMKiller.LOGGER.debug("Registered animation texture: {} ({}x{})", textureId, width, height);
	}

	public void unregisterAnimationTexture(int textureId) {
		animationTextures.remove(textureId);
		visibilityState.remove(textureId);
	}

	public void updateVisibility(int textureId, boolean visible) {
		if (!enabled || textureId <= 0) return;

		Boolean wasVisible = visibilityState.put(textureId, visible);

		if (wasVisible != null && !wasVisible && visible) {
			AnimationTexture animTex = animationTextures.get(textureId);
			if (animTex != null) {
				animTex.needsFullUpdate = true;
				VRAMKiller.LOGGER.debug("Animation texture {} became visible, scheduling full update", textureId);
			}
		}
	}

	public boolean shouldSkipFrameUpdate(int textureId) {
		if (!enabled || textureId <= 0) return false;

		Boolean visible = visibilityState.get(textureId);
		if (visible == null || visible) return false;

		AnimationTexture animTex = animationTextures.get(textureId);
		if (animTex == null) return false;

		if (animTex.skippedFrames < VRAMKillerConfigBase.Animation.maxSkippedFrames) {
			animTex.skippedFrames++;
			return true;
		}

		animTex.skippedFrames = 0;
		return false;
	}

	public void processPendingUpdates() {
		PendingFrameUpdate update;
		while ((update = pendingUpdates.poll()) != null) {
			try {
				update.run();
			} catch (Exception e) {
				VRAMKiller.LOGGER.debug("Failed to process animation update for texture {}: {}",
					update.textureId, e.getMessage());
			}
		}
	}

	public int getAnimationCount() { return animationTextures.size(); }

	public int getInvisibleCount() {
		int count = 0;
		for (Boolean visible : visibilityState.values()) {
			if (!visible) count++;
		}
		return count;
	}

	public void shutdown() {
		animationTextures.clear();
		visibilityState.clear();
		pendingUpdates.clear();
		VRAMKiller.LOGGER.info("Animation texture optimizer shut down");
	}

	private static class AnimationTexture {
		final int textureId;
		final SpriteContents sprite;
		final int width;
		final int height;
		volatile boolean needsFullUpdate;
		volatile int skippedFrames;

		AnimationTexture(int textureId, SpriteContents sprite, int width, int height) {
			this.textureId = textureId;
			this.sprite = sprite;
			this.width = width;
			this.height = height;
			this.needsFullUpdate = false;
			this.skippedFrames = 0;
		}
	}

	private static class PendingFrameUpdate implements Runnable {
		final int textureId;
		private final Runnable action;

		PendingFrameUpdate(int textureId, Runnable action) {
			this.textureId = textureId;
			this.action = action;
		}

		@Override
		public void run() {
			action.run();
		}
	}
}
