package com.vram_killer.client.render;

import java.util.concurrent.ConcurrentHashMap;

public final class PixelDataCache {

	private static final ConcurrentHashMap<String, byte[]> CACHE = new ConcurrentHashMap<>();

	public static void put(String location, byte[] rgbaPixels) {
		CACHE.put(location, rgbaPixels);
	}

	public static byte[] get(String location) {
		return CACHE.get(location);
	}

	public static int size() {
		return CACHE.size();
	}

	public static void clear() {
		CACHE.clear();
	}

	private PixelDataCache() {}
}
