package com.skillforge.studio.dto;

import com.skillforge.studio.entity.WorkspaceEntity;

import java.time.LocalDateTime;

/**
 * 工作区列表视图，不向前端暴露服务器保存路径。
 */
public record WorkspaceSummary(
    String id,
    String name,
    String sourceType,
    String status,
    int fileCount,
    long totalSize,
    LocalDateTime updatedAt
) {
    public static WorkspaceSummary from(WorkspaceEntity workspace) {
        return new WorkspaceSummary(
            workspace.getId(),
            workspace.getName(),
            workspace.getSourceType(),
            workspace.getStatus(),
            workspace.getFileCount(),
            workspace.getTotalSize(),
            workspace.getUpdatedAt()
        );
    }
}
