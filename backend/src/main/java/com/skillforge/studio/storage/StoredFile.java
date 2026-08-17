package com.skillforge.studio.storage;

/**
 * 文件落盘后的不可变结果，用于将文件系统信息写入数据库索引。
 */
public record StoredFile(
    String relativePath,
    String storagePath,
    String originalName,
    String fileType,
    String mimeType,
    long sizeBytes,
    String sha256,
    Integer width,
    Integer height
) {
    public boolean image() {
        return mimeType != null && mimeType.startsWith("image/");
    }
}
