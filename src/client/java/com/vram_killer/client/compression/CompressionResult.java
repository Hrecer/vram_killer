package com.vram_killer.client.compression;

import com.vram_killer.config.VRAMKillerConfigBase;

import java.nio.ByteBuffer;

public class CompressionResult {
	public final boolean success;
	public final ByteBuffer compressedData;
	public final int width;
	public final int height;
	public final VRAMKillerConfigBase.Compression.CompressionFormat format;
	public final int internalFormat;
	public final int originalSize;
	public final int compressedSize;
	public volatile boolean uploaded;

	public CompressionResult(boolean success, ByteBuffer compressedData, int width, int height,
							 VRAMKillerConfigBase.Compression.CompressionFormat format,
							 int internalFormat, int originalSize, int compressedSize) {
		this.success = success;
		this.compressedData = compressedData;
		this.width = width;
		this.height = height;
		this.format = format;
		this.internalFormat = internalFormat;
		this.originalSize = originalSize;
		this.compressedSize = compressedSize;
		this.uploaded = false;
	}

	public static CompressionResult failure() {
		return new CompressionResult(false, null, 0, 0,
			VRAMKillerConfigBase.Compression.CompressionFormat.BC1, 0, 0, 0);
	}

	public boolean isUploaded() { return uploaded; }

	public void markUploaded() { this.uploaded = true; }

	public double getCompressionRatio() {
		return originalSize > 0 ? (double) compressedSize / originalSize : 0.0;
	}
}
