package com.vram_killer.client.atlas;

import com.vram_killer.VRAMKiller;
import com.vram_killer.config.VRAMKillerConfigBase;

import java.util.*;
import java.util.List;

public class SkylineBLPacker {
	private static final int ATLAS_PADDING = 2;
	private static final int MIN_ATLAS_SIZE = 256;
	private static final int MAX_ATLAS_SIZE = 4096;
	
	private final List<Skyline> skylines;
	private final int atlasWidth;
	private final int atlasHeight;
	private int usedArea;
	private int totalTextures;

	public SkylineBLPacker(int width, int height) {
		this.atlasWidth = Math.max(MIN_ATLAS_SIZE, Math.min(MAX_ATLAS_SIZE, width));
		this.atlasHeight = Math.max(MIN_ATLAS_SIZE, Math.min(MAX_ATLAS_SIZE, height));
		this.skylines = new ArrayList<>();
		this.usedArea = 0;
		this.totalTextures = 0;
		
		reset();
	}

	public void reset() {
		skylines.clear();
		skylines.add(new Skyline(0, 0, atlasWidth));
		usedArea = 0;
		totalTextures = 0;
	}

	public PackResult packTexture(int width, int height, boolean canRotate) {
		if (width <= 0 || height <= 0) {
			return PackResult.failure();
		}
		
		int paddedWidth = width + ATLAS_PADDING * 2;
		int paddedHeight = height + ATLAS_PADDING * 2;
		
		PackResult result = findBestPosition(paddedWidth, paddedHeight, canRotate);
		
		if (result.success) {
			insertTexture(result.x, result.y, result.width, result.height);
			usedArea += (result.width - ATLAS_PADDING * 2) * (result.height - ATLAS_PADDING * 2);
			totalTextures++;
		}
		
		return result;
	}

	private PackResult findBestPosition(int width, int height, boolean canRotate) {
		int bestY = Integer.MAX_VALUE;
		int bestX = 0;
		int bestWidth = width;
		int bestHeight = height;
		boolean bestRotated = false;
		int bestSkylineIndex = -1;
		
		for (int i = 0; i < skylines.size(); i++) {
			int y = findPositionForWidth(i, width);
			
			if (y + height <= atlasHeight) {
				if (y < bestY || (y == bestY && skylines.get(i).x < bestX)) {
					bestY = y;
					bestX = skylines.get(i).x;
					bestSkylineIndex = i;
					bestRotated = false;
				}
			}
			
			if (canRotate) {
				y = findPositionForWidth(i, height);
				
				if (y + width <= atlasHeight) {
					if (y < bestY || (y == bestY && skylines.get(i).x < bestX)) {
						bestY = y;
						bestX = skylines.get(i).x;
						bestWidth = height;
						bestHeight = width;
						bestSkylineIndex = i;
						bestRotated = true;
					}
				}
			}
		}
		
		if (bestSkylineIndex == -1) {
			return PackResult.failure();
		}
		
		return new PackResult(bestX + ATLAS_PADDING, bestY + ATLAS_PADDING, 
			bestWidth - ATLAS_PADDING * 2, bestHeight - ATLAS_PADDING * 2,
			true, bestRotated);
	}

	private int findPositionForWidth(int skylineIndex, int width) {
		int x = skylines.get(skylineIndex).x;
		int y = skylines.get(skylineIndex).y;
		int widthLeft = width;
		
		int i = skylineIndex;
		while (widthLeft > 0) {
			if (i >= skylines.size()) return Integer.MAX_VALUE;
			
			x = Math.max(x, skylines.get(i).x);
			y = Math.max(y, skylines.get(i).y);
			
			if (x + width > atlasWidth) return Integer.MAX_VALUE;
			
			widthLeft -= skylines.get(i).width;
			i++;
		}
		
		return y;
	}

	private void insertTexture(int x, int y, int width, int height) {
		Skyline newSkyline = new Skyline(x, y + height, width);
		
		List<Skyline> toRemove = new ArrayList<>();
		int insertionPoint = -1;
		
		for (int i = 0; i < skylines.size(); i++) {
			if (skylines.get(i).x >= x && skylines.get(i).x < x + width) {
				toRemove.add(skylines.get(i));
				
				if (insertionPoint == -1) {
					insertionPoint = i;
				}
			}
		}
		
		for (Skyline s : toRemove) {
			skylines.remove(s);
		}
		
		if (insertionPoint == -1) {
			skylines.add(newSkyline);
		} else {
			skylines.add(insertionPoint, newSkyline);
		}
		
		mergeSkylines();
	}

	private void mergeSkylines() {
		for (int i = 0; i < skylines.size() - 1; i++) {
			Skyline current = skylines.get(i);
			Skyline next = skylines.get(i + 1);
			
			if (current.y == next.y) {
				current.width += next.width;
				skylines.remove(i + 1);
				i--;
			}
		}
	}

	public double getUtilization() {
		return (double) usedArea / (atlasWidth * atlasHeight) * 100.0;
	}

	public int getUsedArea() { return usedArea; }
	public int getTotalArea() { return atlasWidth * atlasHeight; }
	public int getTextureCount() { return totalTextures; }
	public int getAtlasWidth() { return atlasWidth; }
	public int getAtlasHeight() { return atlasHeight; }

	public AtlasMetrics getMetrics() {
		return new AtlasMetrics(atlasWidth, atlasHeight, usedArea, totalTextures, getUtilization());
	}

	public static class PackResult {
		public final int x;
		public final int y;
		public final int width;
		public final int height;
		public final boolean success;
		public final boolean rotated;

		public PackResult(int x, int y, int width, int height, boolean success, boolean rotated) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.success = success;
			this.rotated = rotated;
		}

		public static PackResult failure() {
			return new PackResult(0, 0, 0, 0, false, false);
		}
	}

	private static class Skyline {
		int x;
		int y;
		int width;

		public Skyline(int x, int y, int width) {
			this.x = x;
			this.y = y;
			this.width = width;
		}
	}

	public static class AtlasMetrics {
		public final int width;
		public final int height;
		public final int usedArea;
		public final int wastedArea;
		public final int textureCount;
		public final double utilizationPercent;

		public AtlasMetrics(int width, int height, int usedArea, int textureCount, double utilizationPercent) {
			this.width = width;
			this.height = height;
			this.usedArea = usedArea;
			this.wastedArea = (width * height) - usedArea;
			this.textureCount = textureCount;
			this.utilizationPercent = utilizationPercent;
		}

		public String toString() {
			return String.format("%dx%d | Used: %d/%d (%.1f%%) | Textures: %d",
				width, height, usedArea, width * height, utilizationPercent, textureCount);
		}
	}
}
