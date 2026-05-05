package com.vram_killer.client.compression;

import com.vram_killer.config.VRAMKillerConfigBase;

import java.nio.ByteBuffer;
import java.util.concurrent.Future;

public class CompressionTask {
	private final ByteBuffer sourceData;
	private final int width;
	private final int height;
	private final VRAMKillerConfigBase.Compression.CompressionFormat format;
	private final String texturePath;
	private Future<ByteBuffer> future;
	private long createTime;
	private volatile CompressionStatus status;

	public enum CompressionStatus {
		PENDING,
		COMPRESSING,
		COMPLETED,
		FAILED
	}

	public CompressionTask(ByteBuffer sourceData, int width, int height,
						  VRAMKillerConfigBase.Compression.CompressionFormat format, String texturePath) {
		this.sourceData = sourceData;
		this.width = width;
		this.height = height;
		this.format = format;
		this.texturePath = texturePath;
		this.createTime = System.currentTimeMillis();
		this.status = CompressionStatus.PENDING;
	}

	public ByteBuffer getSourceData() { return sourceData; }
	public int getWidth() { return width; }
	public int getHeight() { return height; }
	public VRAMKillerConfigBase.Compression.CompressionFormat getFormat() { return format; }
	public String getTexturePath() { return texturePath; }
	public Future<ByteBuffer> getFuture() { return future; }
	public long getCreateTime() { return createTime; }
	public CompressionStatus getStatus() { return status; }

	public void setFuture(Future<ByteBuffer> future) {
		this.future = future;
		this.status = CompressionStatus.COMPRESSING;
	}

	public void setStatus(CompressionStatus status) {
		this.status = status;
	}

	public long getElapsedTime() {
		return System.currentTimeMillis() - createTime;
	}
}
