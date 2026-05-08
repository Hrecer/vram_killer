package com.vram_killer.client.compression;

import java.nio.ByteBuffer;
import java.util.concurrent.Future;

public class ConversionTask {
	private final ByteBuffer sourceData;
	private final int width;
	private final int height;
	private final String texturePath;
	private Future<ByteBuffer> future;
	private long createTime;
	private volatile ConversionStatus status;

	public enum ConversionStatus {
		PENDING,
		CONVERTING,
		COMPLETED,
		FAILED
	}

	public ConversionTask(ByteBuffer sourceData, int width, int height, String texturePath) {
		this.sourceData = sourceData;
		this.width = width;
		this.height = height;
		this.texturePath = texturePath;
		this.createTime = System.currentTimeMillis();
		this.status = ConversionStatus.PENDING;
	}

	public ByteBuffer getSourceData() { return sourceData; }
	public int getWidth() { return width; }
	public int getHeight() { return height; }
	public String getTexturePath() { return texturePath; }
	public Future<ByteBuffer> getFuture() { return future; }
	public long getCreateTime() { return createTime; }
	public ConversionStatus getStatus() { return status; }

	public void setFuture(Future<ByteBuffer> future) {
		this.future = future;
		this.status = ConversionStatus.CONVERTING;
	}

	public void setStatus(ConversionStatus status) {
		this.status = status;
	}

	public long getElapsedTime() {
		return System.currentTimeMillis() - createTime;
	}
}
