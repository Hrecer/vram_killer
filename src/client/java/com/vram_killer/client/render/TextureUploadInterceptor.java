package com.vram_killer.client.render;

import com.vram_killer.VRAMKiller;
import com.vram_killer.client.compression.CompressionEngine;
import com.vram_killer.client.compression.CompressionResult;
import com.vram_killer.client.compression.CompressionTask;
import com.vram_killer.config.VRAMKillerConfigBase;
import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class TextureUploadInterceptor {
	private static final int MAX_CACHE_ENTRIES = 2048;
	private static final int MAX_PENDING_UPLOADS = 512;
	private static final long ASYNC_UPLOAD_TIMEOUT_MS = 5000;

	private final ConcurrentHashMap<String, CompressionResult> compressionCache;
	private final ConcurrentLinkedQueue<PendingCompressedUpload> pendingUploads;
	private final ConcurrentHashMap<String, PendingAsyncUpload> asyncUploadQueue;
	private final CompressionEngine compressionEngine;
	private volatile boolean enabled;
	private volatile boolean gl44Available;
	private volatile boolean renderThreadChecked = false;

	private static class PendingAsyncUpload {
		final int textureId;
		final String resourcePath;
		final int width;
		final int height;
		final long submitTime;

		PendingAsyncUpload(int textureId, String resourcePath, int width, int height) {
			this.textureId = textureId;
			this.resourcePath = resourcePath;
			this.width = width;
			this.height = height;
			this.submitTime = System.currentTimeMillis();
		}
	}

	public TextureUploadInterceptor(CompressionEngine engine) {
		this.compressionCache = new ConcurrentHashMap<>();
		this.pendingUploads = new ConcurrentLinkedQueue<>();
		this.asyncUploadQueue = new ConcurrentHashMap<>();
		this.compressionEngine = engine;
		this.enabled = false;
		this.gl44Available = false;
	}

	public void initialize() {
		try {
			this.gl44Available = GL.getCapabilities().OpenGL44;
		} catch (Exception e) {
			this.gl44Available = false;
		}

		this.enabled = VRAMKillerConfigBase.Compression.enabled;
		if (enabled) {
			VRAMKiller.LOGGER.info("Texture upload interceptor initialized - GL 4.4: {}, Format: {}",
				gl44Available, VRAMKillerConfigBase.Compression.colorFormat);
		}
	}

	public boolean isEnabled() { return enabled; }

	public boolean interceptUpload(NativeImage image, String resourcePath) {
		if (!enabled || image == null || resourcePath == null) return false;

		int width = image.getWidth();
		int height = image.getHeight();

		if (width < 4 || height < 4) return false;
		if ((width & 3) != 0 || (height & 3) != 0) return false;

		String cacheKey = buildCacheKey(resourcePath, width, height);

		CompressionResult cached = compressionCache.get(cacheKey);
		if (cached != null && cached.compressedData != null) {
			int textureId = getCurrentlyBoundTexture();
			if (textureId > 0) {
				uploadCompressedToTexture(textureId, cached);
				return true;
			}
		}

		int textureId = getCurrentlyBoundTexture();
		if (textureId <= 0) return false;

		if (VRAMKillerConfigBase.Compression.asyncCompression) {
			final int capturedTextureId = textureId;
			
			PendingAsyncUpload pending = new PendingAsyncUpload(capturedTextureId, resourcePath, width, height);
			asyncUploadQueue.put(resourcePath, pending);
			
			ByteBuffer imageData = extractImageDataOptimized(image);
			if (imageData == null) {
				asyncUploadQueue.remove(resourcePath);
				return false;
			}
			
			compressionEngine.compressTextureAsync(imageData, width, height, resourcePath, result -> {
			if (result != null && result.success && result.compressedData != null) {
				enforceCacheLimit();
				compressionCache.put(cacheKey, result);
				if (pendingUploads.size() < MAX_PENDING_UPLOADS) {
					pendingUploads.add(new PendingCompressedUpload(capturedTextureId, result));
				}
				VRAMKiller.LOGGER.debug("Async compression completed for {}, queued for upload", resourcePath);
				asyncUploadQueue.remove(resourcePath);
			} else {
				asyncUploadQueue.remove(resourcePath);
			}
		});
			
			return false;
		} else {
			try {
				ByteBuffer sourceData = extractImageDataOptimized(image);
				if (sourceData == null) return false;
				
				VRAMKillerConfigBase.Compression.CompressionFormat format = compressionEngine.determineOptimalFormat(resourcePath, sourceData);
				CompressionTask task = new CompressionTask(sourceData, width, height, format, resourcePath);
				CompressionResult result = compressionEngine.compress(task);

				if (result != null && result.success && result.compressedData != null) {
					enforceCacheLimit();
					compressionCache.put(cacheKey, result);
					uploadCompressedToTexture(textureId, result);
					return true;
				}
			} catch (Exception e) {
				VRAMKiller.LOGGER.debug("Sync compression failed for {}: {}", resourcePath, e.getMessage());
			}
			return false;
		}
	}

	public void processPendingUploads() {
		cleanupExpiredAsyncUploads();
		
		PendingCompressedUpload upload;
		while ((upload = pendingUploads.poll()) != null) {
			try {
				if (upload.textureId > 0 && upload.result.compressedData != null) {
					uploadCompressedToTexture(upload.textureId, upload.result);
				}
			} catch (Exception e) {
				VRAMKiller.LOGGER.debug("Failed to upload compressed texture: {}", e.getMessage());
			}
		}
	}

	private void cleanupExpiredAsyncUploads() {
		long now = System.currentTimeMillis();
		asyncUploadQueue.entrySet().removeIf(entry -> {
			boolean expired = now - entry.getValue().submitTime > ASYNC_UPLOAD_TIMEOUT_MS;
			if (expired) {
				VRAMKiller.LOGGER.debug("Async upload expired for {}", entry.getKey());
			}
			return expired;
		});
	}

	private void uploadCompressedToTexture(int textureId, CompressionResult result) {
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);

		int internalFormat = result.internalFormat;
		int width = result.width;
		int height = result.height;

		try {
			if (gl44Available) {
				GL44.glCompressedTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat,
					width, height, 0, result.compressedData);
			} else {
				GL30.glCompressedTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat,
					width, height, 0, result.compressedData);
			}
		} catch (Exception e) {
			VRAMKiller.LOGGER.debug("Compressed texture upload failed for texture {}: {}", textureId, e.getMessage());
		}
	}

	private int getCurrentlyBoundTexture() {
		try {
			int[] textureId = {0};
			GL11.glGetIntegerv(GL11.GL_TEXTURE_BINDING_2D, textureId);
			return textureId[0];
		} catch (Exception e) {
			return 0;
		}
	}

	private ByteBuffer extractImageDataOptimized(NativeImage image) {
		try {
			int width = image.getWidth();
			int height = image.getHeight();
			int size = width * height * 4;

			ByteBuffer buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
			
			long nativePtr = getNativeImagePointer(image);
			if (nativePtr != 0) {
				for (int i = 0; i < size; i++) {
					buffer.put(org.lwjgl.system.MemoryUtil.memGetByte(nativePtr + i));
				}
			} else {
				for (int y = 0; y < height; y++) {
					for (int x = 0; x < width; x++) {
						int pixel = image.getPixel(x, y);
						buffer.put((byte) ((pixel >> 16) & 0xFF));
						buffer.put((byte) ((pixel >> 8) & 0xFF));
						buffer.put((byte) (pixel & 0xFF));
						buffer.put((byte) ((pixel >> 24) & 0xFF));
					}
				}
			}

			buffer.flip();
			return buffer;
		} catch (Exception e) {
			VRAMKiller.LOGGER.debug("Failed to extract image data: {}", e.getMessage());
			return null;
		}
	}

	private long getNativeImagePointer(NativeImage image) {
		try {
			java.lang.reflect.Field field = NativeImage.class.getDeclaredField("pixels");
			field.setAccessible(true);
			return field.getLong(image);
		} catch (Exception e) {
			return 0;
		}
	}

	private void enforceCacheLimit() {
		if (compressionCache.size() >= MAX_CACHE_ENTRIES) {
			Iterator<Map.Entry<String, CompressionResult>> it = compressionCache.entrySet().iterator();
			int toRemove = MAX_CACHE_ENTRIES / 4;
			for (int i = 0; i < toRemove && it.hasNext(); i++) {
				it.next();
				it.remove();
			}
			VRAMKiller.LOGGER.debug("Evicted {} entries from compression cache", toRemove);
		}
	}

	private String buildCacheKey(String resourcePath, int width, int height) {
		return resourcePath + ":" + width + "x" + height;
	}

	public int getCacheSize() { return compressionCache.size(); }

	public long getCacheMemoryUsage() {
		long total = 0;
		for (CompressionResult result : compressionCache.values()) {
			if (result.compressedData != null) {
				total += result.compressedData.remaining();
			}
		}
		return total;
	}

	public double getCompressionRatio() {
		long originalTotal = 0;
		long compressedTotal = 0;
		for (CompressionResult result : compressionCache.values()) {
			originalTotal += result.originalSize;
			compressedTotal += result.compressedSize;
		}
		return originalTotal > 0 ? (double) originalTotal / compressedTotal : 0.0;
	}

	public int getActiveTaskCount() { return compressionCache.size(); }

	public int getPendingAsyncUploadCount() { return asyncUploadQueue.size(); }

	public void shutdown() {
		enabled = false;
		compressionCache.clear();
		pendingUploads.clear();
		asyncUploadQueue.clear();
		VRAMKiller.LOGGER.info("Texture upload interceptor shut down");
	}

	private static class PendingCompressedUpload {
		final int textureId;
		final CompressionResult result;

		PendingCompressedUpload(int textureId, CompressionResult result) {
			this.textureId = textureId;
			this.result = result;
		}
	}
}
