package com.skillforge.studio.dto;

/**
 * 本地或 GitHub 导入完成后的摘要，前端使用 workspaceId 继续请求文件树。
 */
public record WorkspaceImportResult(
    String workspaceId,
    String workspaceName,
    String sourceType,
    int fileCount,
    long totalSize
) {
}
