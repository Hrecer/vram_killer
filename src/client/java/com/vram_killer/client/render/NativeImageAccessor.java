package com.vram_killer.client.render;

import com.mojang.blaze3d.platform.NativeImage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class NativeImageAccessor {
	private static Field pointerField = null;
	private static Method getColorMethod = null;
	private static boolean initialized = false;

	public static void initialize() {
		if (initialized) return;
		initialized = true;

		try {
			for (Field f : NativeImage.class.getDeclaredFields()) {
				if (f.getType() == long.class) {
					f.setAccessible(true);
					pointerField = f;
					break;
				}
			}
		} catch (Exception ignored) {}

		try {
			getColorMethod = NativeImage.class.getDeclaredMethod("getColor", int.class, int.class);
			getColorMethod.setAccessible(true);
		} catch (Exception ignored) {}
	}

	public static long getPointer(NativeImage image) {
		if (!initialized) initialize();
		if (pointerField == null) return 0;

		try {
			return pointerField.getLong(image);
		} catch (Exception ignored) {}

		return 0;
	}

	public static boolean hasPointerAccess() {
		if (!initialized) initialize();
		return pointerField != null;
	}
}
