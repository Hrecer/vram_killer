package com.vram_killer.client.atlas;

import com.vram_killer.VRAMKiller;
import com.vram_killer.config.VRAMKillerConfigBase;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AtlasOptimizer {
	private final SkylineBLPacker packer;
	private final Map<String, AtlasData> atlasRegistry;
	private volatile boolean enabled;

	public AtlasOptimizer() {
		this.packer = new SkylineBLPacker(4096, 4096);
		this.atlasRegistry = new ConcurrentHashMap<>();
		this.enabled = VRAMKillerConfigBase.Compression.enabled;
	}

	public void registerAtlas(String atlasPath, int width, int height) {
		atlasRegistry.computeIfAbsent(atlasPath, k -> 
			new AtlasData(atlasPath, width, height));
	}

	public OptimizedPackResult optimizeSpritePack(String atlasPath, List<SpriteContents> sprites) {
		if (!enabled || sprites == null || sprites.isEmpty()) {
			return OptimizedPackResult.failure();
		}
		
		packer.reset();
		
		List<PackedSprite> packedSprites = new ArrayList<>();
		int totalOriginalArea = 0;
		int successfullyPacked = 0;
		
		for (SpriteContents spriteContents : sprites) {
			int width = spriteContents.width();
			int height = spriteContents.height();
			
			if (width <= 0 || height <= 0) continue;
			
			boolean canRotate = !isSpecialSprite(spriteContents);
			
			SkylineBLPacker.PackResult result = packer.packTexture(width, height, canRotate);
			
			totalOriginalArea += width * height;
			
			if (result.success) {
				PackedSprite packed = new PackedSprite(
					spriteContents,
					result.x, result.y,
					result.width, result.height,
					result.rotated
				);
				packedSprites.add(packed);
				successfullyPacked++;
			}
		}
		
		if (packedSprites.isEmpty()) {
			return OptimizedPackResult.failure();
		}
		
		SkylineBLPacker.AtlasMetrics metrics = packer.getMetrics();
		
		double packingEfficiency = 0;
		if (totalOriginalArea > 0 && metrics.usedArea > 0) {
			packingEfficiency = 100.0 * (double) metrics.usedArea / (metrics.width * metrics.height);
		}

		AtlasData atlasData = atlasRegistry.get(atlasPath);
		if (atlasData != null) {
			atlasData.lastOptimizeTime = System.currentTimeMillis();
			atlasData.totalSpritesProcessed += sprites.size();
			atlasData.currentEfficiency = packingEfficiency;
			atlasData.currentMetrics = metrics;
		}

		return new OptimizedPackResult(true, packedSprites, successfullyPacked,
			sprites.size(), metrics, packingEfficiency);
	}

	private boolean isSpecialSprite(SpriteContents spriteContents) {
		String id = spriteContents.name().toString().toLowerCase();
		
		return id.contains("enchant") ||
			   id.contains("banner") ||
			   id.contains("shield") ||
			   id.contains("shulker") ||
			   id.contains("bed") ||
			   id.contains("conduit") ||
			   id.contains("chest");
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public SkylineBLPacker getPacker() {
		return packer;
	}

	public List<AtlasData> getAllAtlasData() {
		return new ArrayList<>(atlasRegistry.values());
	}

	public static class AtlasData {
		public final String path;
		public final int width;
		public final int height;
		public final long createdTime;
		public volatile long lastOptimizeTime;
		public volatile int totalSpritesProcessed;
		public volatile double currentEfficiency;
		public volatile SkylineBLPacker.AtlasMetrics currentMetrics;

		public AtlasData(String path, int width, int height) {
			this.path = path;
			this.width = width;
			this.height = height;
			this.createdTime = System.currentTimeMillis();
			this.lastOptimizeTime = 0;
			this.totalSpritesProcessed = 0;
			this.currentEfficiency = 0;
			this.currentMetrics = null;
		}
	}

	public static class PackedSprite {
		public final SpriteContents spriteContents;
		public final int x;
		public final int y;
		public final int width;
		public final int height;
		public final boolean rotated;

		public PackedSprite(SpriteContents spriteContents, int x, int y, int width, int height, boolean rotated) {
			this.spriteContents = spriteContents;
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.rotated = rotated;
		}
	}

	public static class OptimizedPackResult {
		public final boolean success;
		public final List<PackedSprite> packedSprites;
		public final int packedCount;
		public final int totalCount;
		public final SkylineBLPacker.AtlasMetrics metrics;
		public final double packingEfficiencyPercent;

		public OptimizedPackResult(boolean success, List<PackedSprite> packedSprites,
								   int packedCount, int totalCount,
								   SkylineBLPacker.AtlasMetrics metrics, double packingEfficiencyPercent) {
			this.success = success;
			this.packedSprites = packedSprites;
			this.packedCount = packedCount;
			this.totalCount = totalCount;
			this.metrics = metrics;
			this.packingEfficiencyPercent = packingEfficiencyPercent;
		}

		public static OptimizedPackResult failure() {
			return new OptimizedPackResult(false, List.of(), 0, 0,
				new SkylineBLPacker.AtlasMetrics(0, 0, 0, 0, 0), 0);
		}

		public String getSummary() {
			return String.format("Analysis: %d/%d sprites packed (%.1f%% packing efficiency)",
				packedCount, totalCount, packingEfficiencyPercent);
		}
	}
}
