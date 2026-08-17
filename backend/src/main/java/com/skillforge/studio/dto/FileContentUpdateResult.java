package com.skillforge.studio.dto;

/** 保存成功后的最新文件版本和工作区大小，前端可直接更新当前状态而无需重新加载目录树。 */
public record FileContentUpdateResult(
    Long fileId,
    long sizeBytes,
    String sha256,
    long workspaceTotalSize
) {
}
