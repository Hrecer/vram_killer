package com.vram_killer.client.cache;

import com.vram_killer.VRAMKiller;
import com.vram_killer.config.VRAMKillerConfigBase;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class TextureCacheManager {
	private static final String CACHE_DIR = ".minecraft/cache/vram_opt";
	private static final String CACHE_INDEX_FILE = "cache_index.dat";
	private static final String MAGIC = "VKTC";
	private static final int VERSION = 4;

	private final Path cacheDirectory;
	private final ConcurrentHashMap<String, CacheEntry> cacheIndex;
	private final AtomicLong totalCacheSize;
	private volatile boolean initialized;
	private final ExecutorService asyncWriteExecutor;

	public TextureCacheManager() {
		this.cacheDirectory = Path.of(CACHE_DIR);
		this.cacheIndex = new ConcurrentHashMap<>();
		this.totalCacheSize = new AtomicLong(0);
		this.initialized = false;
		this.asyncWriteExecutor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "VRAM-CacheWriter");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});
	}

	public void initialize() {
		if (!VRAMKillerConfigBase.Cache.enabled) {
			VRAMKiller.LOGGER.info("Texture cache disabled in configuration");
			return;
		}

		try {
			Files.createDirectories(cacheDirectory);
			loadCacheIndex();
			cleanupStaleEntries();
			enforceSizeLimit();
			initialized = true;

			VRAMKiller.LOGGER.info("Texture cache initialized at {} with {} entries", 
				cacheDirectory.toAbsolutePath(), cacheIndex.size());
		} catch (IOException e) {
			VRAMKiller.LOGGER.error("Failed to initialize texture cache: {}", e.getMessage());
		}
	}

	public CachedTexture getCachedTexture(String texturePath, long sourceFileSize, long sourceLastModified) {
		return getCachedTexture(texturePath, null, sourceFileSize, sourceLastModified);
	}

	public CachedTexture getCachedTexture(String texturePath, String resourcePackName, long sourceFileSize, long sourceLastModified) {
		if (!initialized) return null;

		String cacheKey = generateCacheKey(texturePath, resourcePackName, sourceFileSize, sourceLastModified);
		CacheEntry entry = cacheIndex.get(cacheKey);

		if (entry == null || !isValidEntry(entry, sourceFileSize, sourceLastModified)) {
			return null;
		}

		try {
			return loadCachedData(entry);
		} catch (IOException e) {
			VRAMKiller.LOGGER.debug("Failed to load cached texture {}: {}", texturePath, e.getMessage());
			removeEntry(cacheKey);
			return null;
		}
	}

	public void cacheTexture(String texturePath, ByteBuffer data, int width, int height,
							long sourceFileSize, long sourceLastModified) {
		cacheTexture(texturePath, null, data, width, height, sourceFileSize, sourceLastModified);
	}

	public void cacheTexture(String texturePath, String resourcePackName, ByteBuffer data, 
							int width, int height, long sourceFileSize, long sourceLastModified) {
		if (!initialized || data == null) return;

		String cacheKey = generateCacheKey(texturePath, resourcePackName, sourceFileSize, sourceLastModified);

		final int dataSize = data.remaining();
		final byte[] dataCopy = new byte[dataSize];
		data.mark();
		data.get(dataCopy);
		data.reset();

		asyncWriteExecutor.submit(() -> {
			try {
				Path cacheFile = createCacheFile(cacheKey, texturePath);

				ByteBuffer dataBuffer = ByteBuffer.wrap(dataCopy);
				writeCacheFile(cacheFile, dataBuffer, width, height);

				CacheEntry entry = new CacheEntry(
					cacheKey,
					cacheFile,
					texturePath,
					width,
					height,
					dataSize,
					sourceFileSize,
					sourceLastModified,
					System.currentTimeMillis()
				);

				cacheIndex.put(cacheKey, entry);
				totalCacheSize.addAndGet(entry.cachedSize);
				saveCacheIndex();

				if (totalCacheSize.get() > VRAMKillerConfigBase.Cache.maxSizeMB * 1024L * 1024L) {
					enforceSizeLimit();
				}

			} catch (Exception e) {
				VRAMKiller.LOGGER.debug("Async cache write failed for {}: {}", texturePath, e.getMessage());
			}
		});
	}

	public void invalidateCache(String texturePath) {
		String cacheKey = generateCacheKey(texturePath);
		removeEntry(cacheKey);
	}

	public void clearAllCache() {
		for (String key : cacheIndex.keySet()) {
			removeEntry(key);
		}
		totalCacheSize.set(0);
		saveCacheIndex();
		VRAMKiller.LOGGER.info("All texture cache cleared");
	}

	public long getEstimatedTotalSize() {
		return totalCacheSize.get();
	}

	public int getCacheHitCount() {
		return (int) cacheIndex.values().stream()
			.filter(e -> e.lastAccessed > 0)
			.count();
	}

	public void shutdown() {
		VRAMKiller.LOGGER.info("Shutting down texture cache manager...");

		asyncWriteExecutor.shutdown();

		try {
			if (!asyncWriteExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
				asyncWriteExecutor.shutdownNow();

				if (!asyncWriteExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
					VRAMKiller.LOGGER.warn("Async cache writer did not terminate gracefully");
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			asyncWriteExecutor.shutdownNow();
		}

		saveCacheIndex();

		initialized = false;

		long finalSize = totalCacheSize.get();
		int entryCount = cacheIndex.size();
		cacheIndex.clear();
		totalCacheSize.set(0);

		VRAMKiller.LOGGER.info("Texture cache manager shut down - {} entries cleared ({} MB)", 
			entryCount, finalSize / (1024.0 * 1024.0));
	}

	public boolean isInitialized() {
		return initialized;
	}

	private String generateCacheKey(String texturePath) {
		return generateCacheKey(texturePath, null, 0, 0);
	}

	public String generateCacheKey(String texturePath, String resourcePackName, long sourceFileSize, long sourceLastModified) {
		StringBuilder keyBuilder = new StringBuilder();
		keyBuilder.append(texturePath);

		if (resourcePackName != null && !resourcePackName.isEmpty()) {
			keyBuilder.append("|pack:").append(resourcePackName);
		}

		if (sourceFileSize > 0) {
			keyBuilder.append("|size:").append(sourceFileSize);
		}

		if (sourceLastModified > 0) {
			keyBuilder.append("|modified:").append(sourceLastModified);
		}

		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(keyBuilder.toString().getBytes());

			StringBuilder sb = new StringBuilder();
			for (byte b : digest) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			return String.valueOf(keyBuilder.toString().hashCode());
		}
	}

	public String generateContentHash(ByteBuffer data) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");

			byte[] bytes = new byte[data.remaining()];
			data.mark();
			data.get(bytes);
			data.reset();

			byte[] digest = md.digest(bytes);

			StringBuilder sb = new StringBuilder();
			for (byte b : digest) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (Exception e) {
			VRAMKiller.LOGGER.warn("Failed to generate content hash: {}", e.getMessage());
			return String.valueOf(data.hashCode());
		}
	}

	private Path createCacheFile(String cacheKey, String originalPath) throws IOException {
		String extension = getFileExtension(originalPath);
		String fileName = cacheKey + "." + extension;
		return cacheDirectory.resolve(fileName);
	}

	private String getFileExtension(String path) {
		int dotIndex = path.lastIndexOf('.');
		return dotIndex > 0 ? path.substring(dotIndex + 1) : "cache";
	}

	private void writeCacheFile(Path file, ByteBuffer data, int width, int height) throws IOException {
		try (FileOutputStream fos = new FileOutputStream(file.toFile());
			 FileChannel channel = fos.getChannel()) {

			ByteBuffer header = ByteBuffer.allocate(28);
			header.put(MAGIC.getBytes());
			header.putInt(VERSION);
			header.putInt(width);
			header.putInt(height);
			header.putLong(data.remaining());
			header.flip();

			channel.write(header);
			data.mark();
			channel.write(data);
			data.reset();
		}
	}

	private CachedTexture loadCachedData(CacheEntry entry) throws IOException {
		if (!Files.exists(entry.cacheFile)) {
			return null;
		}

		try (FileInputStream fis = new FileInputStream(entry.cacheFile.toFile());
			 FileChannel channel = fis.getChannel()) {

			ByteBuffer header = ByteBuffer.allocate(32);
			channel.read(header);
			header.flip();

			byte[] magicCheck = new byte[4];
			header.get(magicCheck);
			String magicStr = new String(magicCheck);

			if (!MAGIC.equals(magicStr)) {
				throw new IOException("Invalid cache file magic");
			}

			int version = header.getInt();

			int width, height;
			long dataSize;

			if (version >= 4) {
				width = header.getInt();
				height = header.getInt();
				dataSize = header.getLong();
			} else {
				header.getInt();
				width = header.getInt();
				height = header.getInt();
				dataSize = header.getLong();
			}

			ByteBuffer data = ByteBuffer.allocateDirect((int) dataSize).order(ByteOrder.nativeOrder());
			channel.read(data);
			data.flip();

			entry.lastAccessed = System.currentTimeMillis();

			return new CachedTexture(data, width, height);
		}
	}

	private boolean isValidEntry(CacheEntry entry, long expectedSize, long expectedModified) {
		return Files.exists(entry.cacheFile) && 
			   entry.sourceFileSize == expectedSize && 
			   entry.sourceLastModified == expectedModified;
	}

	private void removeEntry(String cacheKey) {
		CacheEntry entry = cacheIndex.remove(cacheKey);
		if (entry != null) {
			try {
				Files.deleteIfExists(entry.cacheFile);
				totalCacheSize.addAndGet(-entry.cachedSize);
			} catch (IOException e) {
				VRAMKiller.LOGGER.warn("Failed to delete cache file: {}", e.getMessage());
			}
		}
	}

	private void loadCacheIndex() {
		Path indexPath = cacheDirectory.resolve(CACHE_INDEX_FILE);

		if (!Files.exists(indexPath)) return;

		try (DataInputStream dis = new DataInputStream(new FileInputStream(indexPath.toFile()))) {
			String magic = dis.readUTF();
			if (!MAGIC.equals(magic)) return;

			int version = dis.readInt();
			int count = dis.readInt();

			for (int i = 0; i < count; i++) {
				CacheEntry entry = CacheEntry.readFromStream(dis, version);
				if (entry != null && Files.exists(entry.cacheFile)) {
					cacheIndex.put(entry.cacheKey, entry);
					totalCacheSize.addAndGet(entry.cachedSize);
				}
			}
		} catch (IOException e) {
			VRAMKiller.LOGGER.warn("Failed to load cache index: {}", e.getMessage());
		}
	}

	private void saveCacheIndex() {
		Path indexPath = cacheDirectory.resolve(CACHE_INDEX_FILE);

		try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(indexPath.toFile()))) {
			dos.writeUTF(MAGIC);
			dos.writeInt(VERSION);
			dos.writeInt(cacheIndex.size());

			for (CacheEntry entry : cacheIndex.values()) {
				entry.writeToStream(dos);
			}
		} catch (IOException e) {
			VRAMKiller.LOGGER.error("Failed to save cache index: {}", e.getMessage());
		}
	}

	private void cleanupStaleEntries() {
		long now = System.currentTimeMillis();
		long maxAge = 30L * 24 * 60 * 60 * 1000;

		cacheIndex.entrySet().removeIf(entry -> {
			CacheEntry cacheEntry = entry.getValue();
			boolean stale = now - cacheEntry.createdTime > maxAge;

			if (stale) {
				try {
					Files.deleteIfExists(cacheEntry.cacheFile);
					totalCacheSize.addAndGet(-cacheEntry.cachedSize);
				} catch (IOException ignored) {}
			}

			return stale;
		});
	}

	private void enforceSizeLimit() {
		long maxSize = VRAMKillerConfigBase.Cache.maxSizeMB * 1024L * 1024L;

		while (totalCacheSize.get() > maxSize && !cacheIndex.isEmpty()) {
			CacheEntry toRemove = null;

			toRemove = findOldestEntry();

			if (toRemove != null) {
				removeEntry(toRemove.cacheKey);
			} else {
				break;
			}
		}
	}

	private CacheEntry findOldestEntry() {
		CacheEntry oldest = null;

		for (CacheEntry entry : cacheIndex.values()) {
			if (oldest == null || entry.lastAccessed < oldest.lastAccessed) {
				oldest = entry;
			}
		}

		return oldest;
	}

	private CacheEntry findNewestEntry() {
		CacheEntry newest = null;

		for (CacheEntry entry : cacheIndex.values()) {
			if (newest == null || entry.lastAccessed > newest.lastAccessed) {
				newest = entry;
			}
		}

		return newest;
	}

	private static class CacheEntry {
		final String cacheKey;
		final Path cacheFile;
		final String originalPath;
		final int width;
		final int height;
		final long cachedSize;
		final long sourceFileSize;
		final long sourceLastModified;
		final long createdTime;
		volatile long lastAccessed;

		public CacheEntry(String cacheKey, Path cacheFile, String originalPath,
						 int width, int height, long cachedSize, long sourceFileSize,
						 long sourceLastModified, long createdTime) {
			this.cacheKey = cacheKey;
			this.cacheFile = cacheFile;
			this.originalPath = originalPath;
			this.width = width;
			this.height = height;
			this.cachedSize = cachedSize;
			this.sourceFileSize = sourceFileSize;
			this.sourceLastModified = sourceLastModified;
			this.createdTime = createdTime;
			this.lastAccessed = createdTime;
		}

		void writeToStream(DataOutputStream dos) throws IOException {
			dos.writeUTF(cacheKey);
			dos.writeUTF(originalPath);
			dos.writeUTF(cacheFile.getFileName().toString());
			dos.writeInt(width);
			dos.writeInt(height);
			dos.writeLong(cachedSize);
			dos.writeLong(sourceFileSize);
			dos.writeLong(sourceLastModified);
			dos.writeLong(createdTime);
		}

		static CacheEntry readFromStream(DataInputStream dis, int version) throws IOException {
			String cacheKey = dis.readUTF();
			String originalPath = dis.readUTF();
			String fileName = dis.readUTF();

			int width, height;
			long cachedSize, sourceFileSize, sourceLastModified, createdTime;

			if (version >= 4) {
				width = dis.readInt();
				height = dis.readInt();
				cachedSize = dis.readLong();
				sourceFileSize = dis.readLong();
				sourceLastModified = dis.readLong();
				createdTime = dis.readLong();
			} else {
				dis.readInt();
				width = dis.readInt();
				height = dis.readInt();
				cachedSize = dis.readLong();
				sourceFileSize = dis.readLong();
				sourceLastModified = dis.readLong();
				createdTime = dis.readLong();
			}

			return new CacheEntry(
				cacheKey,
				Path.of(CACHE_DIR, fileName),
				originalPath,
				width,
				height,
				cachedSize,
				sourceFileSize,
				sourceLastModified,
				createdTime
			);
		}
	}
}
