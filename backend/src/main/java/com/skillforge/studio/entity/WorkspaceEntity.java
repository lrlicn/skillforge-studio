package com.skillforge.studio.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作区聚合根。首版只创建 LOCAL 类型，后续 GitHub 仓库也复用该模型。
 */
@Data
@TableName("workspaces")
public class WorkspaceEntity {
    @TableId
    private String id;
    private Long userId;
    private String name;
    private String sourceType;
    private String sourceLabel;
    private String repositoryFullName;
    private String repositoryRef;
    private String repositoryBaseCommitSha;
    private String status;
    private Integer fileCount;
    private Long totalSize;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
