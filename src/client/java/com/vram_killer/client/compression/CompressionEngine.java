package com.vram_killer.client.compression;

import com.vram_killer.VRAMKiller;
import com.vram_killer.client.compatibility.SodiumIntegrationBridge;
import com.vram_killer.config.VRAMKillerConfigBase;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBTextureCompressionBPTC;
import org.lwjgl.opengl.EXTTextureCompressionS3TC;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class CompressionEngine {
	private static final int BLOCK_SIZE = 4;
	private static final int BC7_QUALITY_FAST = 0;
	private static final int BC7_QUALITY_MEDIUM = 1;
	private static final int BC7_QUALITY_SLOW = 2;
	private static final ThreadLocal<int[][]> PIXEL_BLOCK_CACHE = ThreadLocal.withInitial(() -> new int[16][4]);
	
	private final ExecutorService compressionPool;
	private final ConcurrentHashMap<String, CompressionTask> activeTasks;
	private final ConcurrentHashMap<String, AsyncCompressionCallback> pendingCallbacks;
	private final AtomicLong totalCompressedSize;
	private final AtomicLong totalOriginalSize;
	
	private volatile boolean compressionSupportDetected = false;
	private boolean bc7Supported;
	private boolean bc5Supported;
	private boolean bc3Supported;
	private boolean bc1Supported;
	private boolean gpuCompressionSupported;

	public interface AsyncCompressionCallback {
		void onCompressionComplete(CompressionResult result);
	}

	public CompressionEngine() {
		final AtomicInteger threadCounter = new AtomicInteger(0);
		int threadCount = VRAMKillerConfigBase.Compression.threadCount;
		this.compressionPool = Executors.newFixedThreadPool(threadCount, r -> {
			Thread t = new Thread(r, "VRAM-Compression-" + threadCounter.getAndIncrement());
			t.setDaemon(true);
			return t;
		});
		
		this.activeTasks = new ConcurrentHashMap<>();
		this.pendingCallbacks = new ConcurrentHashMap<>();
		this.totalCompressedSize = new AtomicLong(0);
		this.totalOriginalSize = new AtomicLong(0);
	}

	public void initialize() {
		VRAMKiller.LOGGER.info("Compression engine initialized with {} threads", VRAMKillerConfigBase.Compression.threadCount);
	}

	public CompressionResult compress(CompressionTask task) {
		ensureCompressionSupportDetected();

		VRAMKillerConfigBase.Compression.CompressionFormat format = task.getFormat();

		if (!isFormatSupported(format)) {
			format = getFallbackFormat(format);
		}

		int originalSize = task.getWidth() * task.getHeight() * 4;
		totalOriginalSize.addAndGet(originalSize);

		try {
			ByteBuffer compressed = switch (format) {
				case BC1 -> compressBC1(task.getSourceData(), task.getWidth(), task.getHeight());
				case BC3 -> compressBC3(task.getSourceData(), task.getWidth(), task.getHeight());
				case BC5 -> compressBC5(task.getSourceData(), task.getWidth(), task.getHeight());
				case BC7 -> compressBC7(task.getSourceData(), task.getWidth(), task.getHeight());
			};

			if (compressed != null) {
				totalCompressedSize.addAndGet(compressed.capacity());
				int internalFormat = getGLInternalFormat(format);
				task.setStatus(CompressionTask.CompressionStatus.COMPLETED);
				return new CompressionResult(true, compressed, task.getWidth(), task.getHeight(),
					format, internalFormat, originalSize, compressed.capacity());
			}
			task.setStatus(CompressionTask.CompressionStatus.FAILED);
			return CompressionResult.failure();
		} catch (Exception e) {
			VRAMKiller.LOGGER.debug("Compression failed for {}: {}", task.getTexturePath(), e.getMessage());
			task.setStatus(CompressionTask.CompressionStatus.FAILED);
			return CompressionResult.failure();
		}
	}

	public java.util.concurrent.Future<CompressionResult> submitCompression(CompressionTask task) {
		return compressionPool.submit(() -> compress(task));
	}

	private void ensureCompressionSupportDetected() {
		if (!compressionSupportDetected) {
			synchronized (this) {
				if (!compressionSupportDetected) {
					detectCompressionSupport();
					compressionSupportDetected = true;
				}
			}
		}
	}

	private void detectCompressionSupport() {
		try {
			String extensions = GL11.glGetString(GL11.GL_EXTENSIONS);
			
			bc1Supported = extensions != null && (extensions.contains("GL_EXT_texture_compression_s3tc") || 
				extensions.contains("GL_ARB_texture_compression_bptc"));
			bc3Supported = bc1Supported;
			bc5Supported = extensions != null && (extensions.contains("GL_ATI_texture_compression_3dc") ||
				extensions.contains("GL_ARB_texture_compression_rgtc"));
			bc7Supported = extensions != null && extensions.contains("GL_ARB_texture_compression_bptc");
			
			gpuCompressionSupported = extensions != null && extensions.contains("GL_ARB_texture_compression");
			
			VRAMKiller.LOGGER.info("Compression format support - BC1: {} | BC3: {} | BC5: {} | BC7: {} | GPU: {}", 
				bc1Supported, bc3Supported, bc5Supported, bc7Supported, gpuCompressionSupported);
		} catch (Exception e) {
			VRAMKiller.LOGGER.warn("Could not detect compression support, using defaults: {}", e.getMessage());
			bc1Supported = true;
			bc3Supported = true;
			bc5Supported = false;
			bc7Supported = false;
			gpuCompressionSupported = false;
		}
	}

	public CompressionResult compressTexture(ByteBuffer sourceData, int width, int height, String texturePath) {
		return compressTextureAsync(sourceData, width, height, texturePath, null);
	}

	public CompressionResult compressTextureAsync(ByteBuffer sourceData, int width, int height, 
												  String texturePath, AsyncCompressionCallback callback) {
		ensureCompressionSupportDetected();

		VRAMKillerConfigBase.Compression.CompressionFormat format = determineOptimalFormat(texturePath, sourceData);

		if (!isFormatSupported(format)) {
			format = getFallbackFormat(format);
		}

		int originalSize = width * height * 4;
		totalOriginalSize.addAndGet(originalSize);

		CompressionTask task = new CompressionTask(sourceData, width, height, format, texturePath);
		activeTasks.put(texturePath, task);
		
		if (callback != null) {
			pendingCallbacks.put(texturePath, callback);
		}

		Future<ByteBuffer> future = compressionPool.submit(() -> performCompressionWithCallback(task));
		task.setFuture(future);

		return new CompressionResult(false, null, width, height, format,
			getGLInternalFormat(format), originalSize, 0);
	}

	private ByteBuffer performCompressionWithCallback(CompressionTask task) {
		ByteBuffer compressed = performCompression(task);
		
		if (compressed != null) {
			AsyncCompressionCallback callback = pendingCallbacks.remove(task.getTexturePath());
			if (callback != null) {
				int internalFormat = getGLInternalFormat(task.getFormat());
				CompressionResult result = new CompressionResult(
					true, compressed, task.getWidth(), task.getHeight(),
					task.getFormat(), internalFormat, 
					task.getWidth() * task.getHeight() * 4, compressed.capacity()
				);
				callback.onCompressionComplete(result);
			}
		} else {
			pendingCallbacks.remove(task.getTexturePath());
		}
		
		return compressed;
	}

	public ByteBuffer waitForCompression(CompressionTask task, long timeoutMs) throws Exception {
		if (!task.getFuture().isDone()) {
			try {
				return task.getFuture().get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				VRAMKiller.LOGGER.warn("Compression timed out for {} after {}ms", task.getTexturePath(), timeoutMs);
				return null;
			} catch (Exception e) {
				VRAMKiller.LOGGER.error("Compression failed for {}: {}", task.getTexturePath(), e.getMessage());
				return null;
			}
		}
		return task.getFuture().get();
	}

	private ByteBuffer performCompression(CompressionTask task) {
		long startTime = System.nanoTime();
		
		ByteBuffer compressed = switch (task.getFormat()) {
			case BC1 -> compressBC1(task.getSourceData(), task.getWidth(), task.getHeight());
			case BC3 -> compressBC3(task.getSourceData(), task.getWidth(), task.getHeight());
			case BC5 -> compressBC5(task.getSourceData(), task.getWidth(), task.getHeight());
			case BC7 -> compressBC7(task.getSourceData(), task.getWidth(), task.getHeight());
		};
		
		long duration = (System.nanoTime() - startTime) / 1_000_000;
		
		if (compressed != null) {
			totalCompressedSize.addAndGet(compressed.capacity());
			
			if (duration > 100) {
				VRAMKiller.LOGGER.debug("Slow compression for {} ({}x{}): {}ms", 
					task.getTexturePath(), task.getWidth(), task.getHeight(), duration);
			}
		}
		
		activeTasks.remove(task.getTexturePath());
		return compressed;
	}

	public VRAMKillerConfigBase.Compression.CompressionFormat determineOptimalFormat(String texturePath, ByteBuffer data) {
		if (isNormalMap(texturePath)) {
			return VRAMKillerConfigBase.Compression.normalFormat;
		}
		
		if (isGUIOrFont(texturePath)) {
			return VRAMKillerConfigBase.Compression.colorFormat;
		}
		
		if (hasTransparency(data)) {
			return VRAMKillerConfigBase.Compression.colorFormat;
		}
		
		return VRAMKillerConfigBase.Compression.backgroundFormat;
	}

	private boolean isNormalMap(String path) {
		return path.toLowerCase().contains("_normal") || 
			   path.toLowerCase().contains("_n.") ||
			   path.toLowerCase().contains("/normal/");
	}

	private boolean isGUIOrFont(String path) {
		String lower = path.toLowerCase();
		return lower.contains("/gui/") || lower.contains("/font/") || lower.contains("/ui/");
	}

	private boolean hasTransparency(ByteBuffer data) {
		int size = data.remaining() / 4;
		int pos = data.position();
		int checkCount = Math.min(size, 65536);
		int step = Math.max(1, size / checkCount);

		for (int i = 0; i < size; i += step) {
			int offset = pos + i * 4;
			if (offset + 3 < data.limit() && data.get(offset + 3) < 255) {
				return true;
			}
		}

		return false;
	}

	private ByteBuffer compressBC1(ByteBuffer src, int width, int height) {
		int blocksX = (width + BLOCK_SIZE - 1) / BLOCK_SIZE;
		int blocksY = (height + BLOCK_SIZE - 1) / BLOCK_SIZE;
		ByteBuffer dst = allocateBuffer(blocksX * blocksY * 8);
		
		for (int by = 0; by < blocksY; by++) {
			for (int bx = 0; bx < blocksX; bx++) {
				compressBC1BlockOptimized(src, width, height, bx, by, dst);
			}
		}
		
		dst.flip();
		return dst;
	}

	private void compressBC1BlockOptimized(ByteBuffer src, int width, int height, int bx, int by, ByteBuffer dst) {
		int[][] pixels = extractPixelBlock4x4(src, width, height, bx, by);
		
		int[] minMax = findMinMaxColor565(pixels);
		int c0 = minMax[0];
		int c1 = minMax[1];
		
		if (c0 < c1) {
			int temp = c0;
			c0 = c1;
			c1 = temp;
		}
		
		dst.putShort((short) c0);
		dst.putShort((short) c1);
		
		long indices = computeBC1Indices(pixels, c0, c1);
		dst.putLong(indices);
	}

	private int[][] extractPixelBlock4x4(ByteBuffer src, int width, int height, int bx, int by) {
		int[][] pixels = PIXEL_BLOCK_CACHE.get();
		int idx = 0;
		
		for (int y = by * BLOCK_SIZE; y < Math.min((by + 1) * BLOCK_SIZE, height); y++) {
			for (int x = bx * BLOCK_SIZE; x < Math.min((bx + 1) * BLOCK_SIZE, width); x++) {
				int pixelIdx = (y * width + x) * 4;
				pixels[idx][0] = src.get(pixelIdx) & 0xFF;
				pixels[idx][1] = src.get(pixelIdx + 1) & 0xFF;
				pixels[idx][2] = src.get(pixelIdx + 2) & 0xFF;
				pixels[idx][3] = src.get(pixelIdx + 3) & 0xFF;
				idx++;
			}
		}
		
		while (idx < 16) {
			pixels[idx][0] = pixels[0][0];
			pixels[idx][1] = pixels[0][1];
			pixels[idx][2] = pixels[0][2];
			pixels[idx][3] = pixels[0][3];
			idx++;
		}
		
		return pixels;
	}

	private int[] findMinMaxColor565(int[][] pixels) {
		int minR = 255, maxR = 0, minG = 255, maxG = 0, minB = 255, maxB = 0;
		
		for (int i = 0; i < 16; i++) {
			minR = Math.min(minR, pixels[i][0]); maxR = Math.max(maxR, pixels[i][0]);
			minG = Math.min(minG, pixels[i][1]); maxG = Math.max(maxG, pixels[i][1]);
			minB = Math.min(minB, pixels[i][2]); maxB = Math.max(maxB, pixels[i][2]);
		}
		
		int c0 = rgbTo565(maxR, maxG, maxB);
		int c1 = rgbTo565(minR, minG, minB);
		
		return new int[]{c0, c1};
	}

	private int rgbTo565(int r, int g, int b) {
		return ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);
	}

	private long computeBC1Indices(int[][] pixels, int c0, int c1) {
		long indices = 0;
		
		int r0 = (c0 >> 11) & 0x1F, g0 = (c0 >> 5) & 0x3F, b0 = c0 & 0x1F;
		int r1 = (c1 >> 11) & 0x1F, g1 = (c1 >> 5) & 0x3F, b1 = c1 & 0x1F;
		
		int[][] palette = generateBC1Palette(r0, g0, b0, r1, g1, b1, c0 > c1);
		
		for (int i = 0; i < 16; i++) {
			int bestIndex = findBestPaletteIndex(pixels[i], palette);
			indices |= (long) bestIndex << (i * 2);
		}
		
		return indices;
	}

	private int[][] generateBC1Palette(int r0, int g0, int b0, int r1, int g1, int b1, boolean fourColor) {
		int[][] palette = new int[4][3];
		
		palette[0][0] = (r0 << 3) | (r0 >> 2);
		palette[0][1] = (g0 << 2) | (g0 >> 4);
		palette[0][2] = (b0 << 3) | (b0 >> 2);
		
		palette[1][0] = (r1 << 3) | (r1 >> 2);
		palette[1][1] = (g1 << 2) | (g1 >> 4);
		palette[1][2] = (b1 << 3) | (b1 >> 2);
		
		if (fourColor) {
			palette[2][0] = ((2 * palette[0][0] + palette[1][0]) / 3);
			palette[2][1] = ((2 * palette[0][1] + palette[1][1]) / 3);
			palette[2][2] = ((2 * palette[0][2] + palette[1][2]) / 3);
			
			palette[3][0] = ((palette[0][0] + 2 * palette[1][0]) / 3);
			palette[3][1] = ((palette[0][1] + 2 * palette[1][1]) / 3);
			palette[3][2] = ((palette[0][2] + 2 * palette[1][2]) / 3);
		} else {
			palette[2][0] = ((palette[0][0] + palette[1][0]) / 2);
			palette[2][1] = ((palette[0][1] + palette[1][1]) / 2);
			palette[2][2] = ((palette[0][2] + palette[1][2]) / 2);
			
			palette[3][0] = 0;
			palette[3][1] = 0;
			palette[3][2] = 0;
		}
		
		return palette;
	}

	private int findBestPaletteIndex(int[] pixel, int[][] palette) {
		int bestIndex = 0;
		int minDist = Integer.MAX_VALUE;
		
		for (int i = 0; i < 4; i++) {
			int dist = colorDistanceSquared(pixel, palette[i]);
			if (dist < minDist) {
				minDist = dist;
				bestIndex = i;
			}
		}
		
		return bestIndex;
	}

	private int colorDistanceSquared(int[] c1, int[] c2) {
		int dr = c1[0] - c2[0];
		int dg = c1[1] - c2[1];
		int db = c1[2] - c2[2];
		return dr * dr + dg * dg + db * db;
	}

	private ByteBuffer compressBC3(ByteBuffer src, int width, int height) {
		int blocksX = (width + BLOCK_SIZE - 1) / BLOCK_SIZE;
		int blocksY = (height + BLOCK_SIZE - 1) / BLOCK_SIZE;
		ByteBuffer dst = allocateBuffer(blocksX * blocksY * 16);

		for (int by = 0; by < blocksY; by++) {
			for (int bx = 0; bx < blocksX; bx++) {
				compressDXT5AlphaBlock(src, width, height, bx, by, dst);
				compressBC1BlockOptimized(src, width, height, bx, by, dst);
			}
		}

		dst.flip();
		return dst;
	}

	private void compressDXT5AlphaBlock(ByteBuffer src, int width, int height, int bx, int by, ByteBuffer dst) {
		byte[] alphas = new byte[16];
		int idx = 0;
		
		for (int y = by * BLOCK_SIZE; y < Math.min((by + 1) * BLOCK_SIZE, height); y++) {
			for (int x = bx * BLOCK_SIZE; x < Math.min((bx + 1) * BLOCK_SIZE, width); x++) {
				int pixelIdx = (y * width + x) * 4 + 3;
				alphas[idx++] = (byte) (src.get(pixelIdx) & 0xFF);
			}
		}
		
		while (idx < 16) {
			alphas[idx++] = alphas[0];
		}
		
		byte alpha0 = -128, alpha1 = 127;
		for (byte a : alphas) {
			int ua = a & 0xFF;
			alpha0 = (byte) Math.max(alpha0 & 0xFF, ua);
			alpha1 = (byte) Math.min(alpha1 & 0xFF, ua);
		}
		
		dst.put(alpha0);
		dst.put(alpha1);
		
		long alphaIndices = computeDXT5AlphaIndices(alphas, alpha0, alpha1);
		dst.putLong(alphaIndices);
	}

	private long computeDXT5AlphaIndices(byte[] alphas, byte alpha0, byte alpha1) {
		long indices = 0;
		int a0 = alpha0 & 0xFF;
		int a1 = alpha1 & 0xFF;
		
		byte[] alphasOut = new byte[8];
		
		if (a0 > a1) {
			alphasOut[0] = (byte) a0;
			alphasOut[1] = (byte) a1;
			alphasOut[2] = (byte) ((6 * a0 + 1 * a1) / 7);
			alphasOut[3] = (byte) ((5 * a0 + 2 * a1) / 7);
			alphasOut[4] = (byte) ((4 * a0 + 3 * a1) / 7);
			alphasOut[5] = (byte) ((3 * a0 + 4 * a1) / 7);
			alphasOut[6] = (byte) ((2 * a0 + 5 * a1) / 7);
			alphasOut[7] = (byte) ((1 * a0 + 6 * a1) / 7);
		} else {
			alphasOut[0] = (byte) a0;
			alphasOut[1] = (byte) a1;
			alphasOut[2] = (byte) ((4 * a0 + 1 * a1) / 5);
			alphasOut[3] = (byte) ((3 * a0 + 2 * a1) / 5);
			alphasOut[4] = (byte) ((2 * a0 + 3 * a1) / 5);
			alphasOut[5] = (byte) ((1 * a0 + 4 * a1) / 5);
			alphasOut[6] = 0;
			alphasOut[7] = (byte) 255;
		}
		
		for (int i = 0; i < 16; i++) {
			int bestIndex = 0;
			int minDist = Integer.MAX_VALUE;
			
			for (int j = 0; j < 8; j++) {
				int dist = Math.abs((alphas[i] & 0xFF) - (alphasOut[j] & 0xFF));
				if (dist < minDist) {
					minDist = dist;
					bestIndex = j;
				}
			}
			
			indices |= (long) bestIndex << (i * 3);
		}
		
		return indices;
	}

	private ByteBuffer compressBC5(ByteBuffer src, int width, int height) {
		int blocksX = (width + BLOCK_SIZE - 1) / BLOCK_SIZE;
		int blocksY = (height + BLOCK_SIZE - 1) / BLOCK_SIZE;
		ByteBuffer dst = allocateBuffer(blocksX * blocksY * 16);

		for (int by = 0; by < blocksY; by++) {
			for (int bx = 0; bx < blocksX; bx++) {
				compressBC5Block(src, width, height, bx, by, dst);
			}
		}
		
		dst.flip();
		return dst;
	}

	private void compressBC5Block(ByteBuffer src, int width, int height, int bx, int by, ByteBuffer dst) {
		byte[] redChannel = new byte[16];
		byte[] greenChannel = new byte[16];
		int idx = 0;
		
		for (int y = by * BLOCK_SIZE; y < Math.min((by + 1) * BLOCK_SIZE, height); y++) {
			for (int x = bx * BLOCK_SIZE; x < Math.min((bx + 1) * BLOCK_SIZE, width); x++) {
				int pixelIdx = (y * width + x) * 4;
				redChannel[idx] = (byte) (src.get(pixelIdx) & 0xFF);
				greenChannel[idx] = (byte) (src.get(pixelIdx + 1) & 0xFF);
				idx++;
			}
		}
		
		while (idx < 16) {
			redChannel[idx] = redChannel[0];
			greenChannel[idx] = greenChannel[0];
			idx++;
		}
		
		compressSingleChannel(redChannel, dst);
		compressSingleChannel(greenChannel, dst);
	}

	private void compressSingleChannel(byte[] channel, ByteBuffer dst) {
		byte minVal = 127, maxVal = -128;
		for (byte v : channel) {
			int uv = v & 0xFF;
			minVal = (byte) Math.min(minVal & 0xFF, uv);
			maxVal = (byte) Math.max(maxVal & 0xFF, uv);
		}
		
		dst.put(minVal);
		dst.put(maxVal);
		
		long indices = computeRGTCIndices(channel, minVal, maxVal);
		dst.putLong(indices);
	}

	private long computeRGTCIndices(byte[] channel, byte minVal, byte maxVal) {
		long indices = 0;
		int min = minVal & 0xFF;
		int max = maxVal & 0xFF;
		
		int[] palette = new int[8];
		palette[0] = max;
		palette[1] = min;
		palette[2] = ((6 * max + 1 * min) / 7);
		palette[3] = ((5 * max + 2 * min) / 7);
		palette[4] = ((4 * max + 3 * min) / 7);
		palette[5] = ((3 * max + 4 * min) / 7);
		palette[6] = ((2 * max + 5 * min) / 7);
		palette[7] = ((1 * max + 6 * min) / 7);
		
		for (int i = 0; i < 16; i++) {
			int bestIndex = 0;
			int minDist = Integer.MAX_VALUE;
			
			int val = channel[i] & 0xFF;
			for (int j = 0; j < 8; j++) {
				int dist = Math.abs(val - palette[j]);
				if (dist < minDist) {
					minDist = dist;
					bestIndex = j;
				}
			}
			
			indices |= (long) bestIndex << (i * 3);
		}
		
		return indices;
	}

	private ByteBuffer compressBC7(ByteBuffer src, int width, int height) {
		int blocksX = (width + BLOCK_SIZE - 1) / BLOCK_SIZE;
		int blocksY = (height + BLOCK_SIZE - 1) / BLOCK_SIZE;
		ByteBuffer dst = allocateBuffer(blocksX * blocksY * 16);

		for (int by = 0; by < blocksY; by++) {
			for (int bx = 0; bx < blocksX; bx++) {
				int[][] pixels = extractPixelBlock4x4(src, width, height, bx, by);
				encodeBC7BlockMode6Fast(pixels, dst);
			}
		}

		dst.flip();
		return dst;
	}

	private void encodeBC7BlockMode6Fast(int[][] pixels, ByteBuffer dst) {
		int minR = 255, maxR = 0, minG = 255, maxG = 0, minB = 255, maxB = 0, minA = 255, maxA = 0;

		for (int i = 0; i < 16; i++) {
			minR = Math.min(minR, pixels[i][0]); maxR = Math.max(maxR, pixels[i][0]);
			minG = Math.min(minG, pixels[i][1]); maxG = Math.max(maxG, pixels[i][1]);
			minB = Math.min(minB, pixels[i][2]); maxB = Math.max(maxB, pixels[i][2]);
			minA = Math.min(minA, pixels[i][3]); maxA = Math.max(maxA, pixels[i][3]);
		}

		int r0 = maxR >> 1, g0 = maxG >> 1, b0 = maxB >> 1, a0 = maxA >> 1;
		int r1 = minR >> 1, g1 = minG >> 1, b1 = minB >> 1, a1 = minA >> 1;

		long blockLow = 64L;
		long blockHigh = 0L;
		int bitPos = 7;

		blockLow |= ((long) a0 << bitPos); bitPos += 7;
		blockLow |= ((long) r0 << bitPos); bitPos += 7;
		blockLow |= ((long) g0 << bitPos); bitPos += 7;
		blockLow |= ((long) b0 << bitPos); bitPos += 7;
		blockLow |= ((long) a1 << bitPos); bitPos += 7;
		blockLow |= ((long) r1 << bitPos); bitPos += 7;
		blockLow |= ((long) g1 << bitPos); bitPos += 7;
		blockLow |= ((long) b1 << bitPos); bitPos += 7;

		bitPos = 64;

		for (int i = 0; i < 16; i++) {
			int bestIdx = findBestPaletteIndexBC7Fast(pixels[i], r0, g0, b0, a0, r1, g1, b1, a1);
			if (bitPos <= 60) {
				blockLow |= ((long) bestIdx << bitPos);
				bitPos += 4;
			} else {
				int bitsInLow = 64 - bitPos;
				blockLow |= ((long) (bestIdx & ((1 << bitsInLow) - 1)) << bitPos);
				blockHigh |= ((long) (bestIdx >> bitsInLow));
				bitPos = 4 - bitsInLow;
			}
		}

		dst.putLong(Long.reverseBytes(blockLow));
		dst.putLong(Long.reverseBytes(blockHigh));
	}

	private int findBestPaletteIndexBC7Fast(int[] pixel, int r0, int g0, int b0, int a0, int r1, int g1, int b1, int a1) {
		int bestIdx = 0;
		int minDist = Integer.MAX_VALUE;

		for (int j = 0; j < 16; j++) {
			int pa = (j * a0 + (15 - j) * a1 + 7) / 15;
			int pr = (j * r0 + (15 - j) * r1 + 7) / 15;
			int pg = (j * g0 + (15 - j) * g1 + 7) / 15;
			int pb = (j * b0 + (15 - j) * b1 + 7) / 15;
			
			int da = (pixel[3] >> 1) - pa;
			int dr = (pixel[0] >> 1) - pr;
			int dg = (pixel[1] >> 1) - pg;
			int db = (pixel[2] >> 1) - pb;
			int dist = da * da + dr * dr + dg * dg + db * db;
			
			if (dist < minDist) {
				minDist = dist;
				bestIdx = j;
			}
		}

		return bestIdx;
	}

	private boolean isFormatSupported(VRAMKillerConfigBase.Compression.CompressionFormat format) {
		return switch (format) {
			case BC1 -> bc1Supported;
			case BC3 -> bc3Supported;
			case BC5 -> bc5Supported;
			case BC7 -> bc7Supported;
		};
	}

	private VRAMKillerConfigBase.Compression.CompressionFormat getFallbackFormat(VRAMKillerConfigBase.Compression.CompressionFormat format) {
		if (format == VRAMKillerConfigBase.Compression.CompressionFormat.BC7 && !bc7Supported) return VRAMKillerConfigBase.Compression.CompressionFormat.BC3;
		if (format == VRAMKillerConfigBase.Compression.CompressionFormat.BC5 && !bc5Supported) return VRAMKillerConfigBase.Compression.CompressionFormat.BC3;
		if (format == VRAMKillerConfigBase.Compression.CompressionFormat.BC3 && !bc3Supported) return VRAMKillerConfigBase.Compression.CompressionFormat.BC1;
		return VRAMKillerConfigBase.Compression.CompressionFormat.BC1;
	}

	private ByteBuffer allocateBuffer(int size) {
		return BufferUtils.createByteBuffer(size);
	}

	public int getGLInternalFormat(VRAMKillerConfigBase.Compression.CompressionFormat format) {
		return switch (format) {
			case BC1 -> EXTTextureCompressionS3TC.GL_COMPRESSED_RGB_S3TC_DXT1_EXT;
			case BC3 -> EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT5_EXT;
			case BC5 -> GL30.GL_COMPRESSED_RG_RGTC2;
			case BC7 -> ARBTextureCompressionBPTC.GL_COMPRESSED_RGBA_BPTC_UNORM_ARB;
		};
	}

	public static int getGLFormatStatic(VRAMKillerConfigBase.Compression.CompressionFormat format) {
		return switch (format) {
			case BC1 -> EXTTextureCompressionS3TC.GL_COMPRESSED_RGB_S3TC_DXT1_EXT;
			case BC3 -> EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT5_EXT;
			case BC5 -> GL30.GL_COMPRESSED_RG_RGTC2;
			case BC7 -> ARBTextureCompressionBPTC.GL_COMPRESSED_RGBA_BPTC_UNORM_ARB;
		};
	}

	public void shutdown() {
		compressionPool.shutdown();
		activeTasks.clear();
		VRAMKiller.LOGGER.info("Compression engine shut down. Total compressed: {} bytes from {} bytes original",
			totalCompressedSize.get(), totalOriginalSize.get());
	}

	public double getCompressionRatio() {
		long compressed = totalCompressedSize.get();
		long original = totalOriginalSize.get();
		return original > 0 ? (double) compressed / original : 0.0;
	}

	public int getActiveTaskCount() {
		return activeTasks.size();
	}
}
