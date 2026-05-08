package com.vram_killer.client.cache;

import java.nio.ByteBuffer;

public class CachedTexture {
	private final ByteBuffer data;
	private final int width;
	private final int height;
	private final long cacheTime;

	public CachedTexture(ByteBuffer data, int width, int height) {
		this.data = data;
		this.width = width;
		this.height = height;
		this.cacheTime = System.currentTimeMillis();
	}

	public ByteBuffer getData() { return data; }
	public int getWidth() { return width; }
	public int getHeight() { return height; }
	public long getCacheTime() { return cacheTime; }

	public boolean isExpired(long maxAgeMs) {
		return System.currentTimeMillis() - cacheTime > maxAgeMs;
	}

	public int getSizeBytes() {
		return data != null ? data.remaining() : 0;
	}
}
