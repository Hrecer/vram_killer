package com.vram_killer.client.render;

import com.vram_killer.VRAMKiller;
import com.vram_killer.client.VRAMKillerClient;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TextureUploadInterceptor {
	private volatile boolean enabled = true;

	private final AtomicInteger convertedCount = new AtomicInteger(0);
	private final AtomicInteger skippedCount = new AtomicInteger(0);
	private final AtomicLong savedBytes = new AtomicLong(0);

	public TextureUploadInterceptor() {
		this.enabled = true;
	}

	public void initialize() {
		this.enabled = true;
		VRAMKiller.LOGGER.info("[VRAM-5551] TextureUploadInterceptor initialized - RGBA5551 mode");
	}

	public boolean isEnabled() { return enabled; }

	public boolean convertToRGB5A1(int glTextureId, int width, int height, String resourcePath, byte[] rgbaPixels) {
		if (!enabled || glTextureId <= 0 || rgbaPixels == null) return false;
		if (width < 4 || height < 4) return false;
		if (rgbaPixels.length != width * height * 4) return false;

		long t0 = System.nanoTime();

		try {
			boolean allBinaryAlpha = checkBinaryAlpha(rgbaPixels);
			if (!allBinaryAlpha) {
				skippedCount.incrementAndGet();
				if (convertedCount.get() <= 10) {
					VRAMKiller.LOGGER.debug("[VRAM-5551] SKIP non-binary alpha: {} ({}x{})", resourcePath, width, height);
				}
				return false;
			}

			short[] rgb5a1Data = convertToRGB5A1(rgbaPixels, width, height);

			GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);

			java.nio.ShortBuffer shortBuffer = java.nio.ByteBuffer.allocateDirect(rgb5a1Data.length * 2)
				.order(ByteOrder.nativeOrder()).asShortBuffer();
			shortBuffer.put(rgb5a1Data);
			shortBuffer.flip();

			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB5_A1,
				width, height, 0,
				GL11.GL_RGBA, GL12.GL_UNSIGNED_SHORT_5_5_5_1,
				shortBuffer);

			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);

			int count = convertedCount.incrementAndGet();
			long originalSize = (long) width * height * 4;
			long newSize = (long) width * height * 2;
			savedBytes.addAndGet(originalSize - newSize);
			long ms = (System.nanoTime() - t0) / 1_000_000;

			if (count <= 15) {
				float origKB = originalSize / 1024.0f;
				float newKB = newSize / 1024.0f;
				VRAMKiller.LOGGER.info("[VRAM-5551] ✅ CONVERTED: {} {}x{} ({}KB→{}KB, {} ms) [#{}]",
					resourcePath, width, height, String.format("%.1f", origKB), String.format("%.1f", newKB), ms, count);
			} else if (count == 16) {
				VRAMKiller.LOGGER.info("[VRAM-5551] ... (suppressing further per-texture logs)");
			}

			return true;

		} catch (Exception e) {
			VRAMKiller.LOGGER.warn("[VRAM-5551] Error converting {}: {}", resourcePath, e.getMessage());
			return false;
		}
	}

	private boolean checkBinaryAlpha(byte[] rgbaPixels) {
		for (int i = 3; i < rgbaPixels.length; i += 4) {
			byte a = rgbaPixels[i];
			if (a != 0 && a != (byte) 255) {
				return false;
			}
		}
		return true;
	}

	private short[] convertToRGB5A1(byte[] rgbaPixels, int width, int height) {
		short[] output = new short[width * height];
		int pixelCount = width * height;

		for (int i = 0; i < pixelCount; i++) {
			int idx = i * 4;
			int r = (rgbaPixels[idx] & 0xFF) >> 3;
			int g = (rgbaPixels[idx + 1] & 0xFF) >> 3;
			int b = (rgbaPixels[idx + 2] & 0xFF) >> 3;
			int a = (rgbaPixels[idx + 3] & 0xFF) > 127 ? 1 : 0;

			output[i] = (short) ((r << 11) | (g << 6) | (b << 1) | a);
		}

		return output;
	}

	public void printStats() {
		float savedMB = savedBytes.get() / (1024.0f * 1024.0f);
		VRAMKiller.LOGGER.info("[VRAM-5551] === Stats === Converted: {}, Skipped: {}, Saved: {} MB",
			convertedCount.get(), skippedCount.get(), String.format("%.1f", savedMB));
	}

	public void shutdown() {
		enabled = false;
		printStats();
	}

	public int getConvertedCount() { return convertedCount.get(); }
	public int getSkippedCount() { return skippedCount.get(); }
	public long getSavedBytes() { return savedBytes.get(); }
}
