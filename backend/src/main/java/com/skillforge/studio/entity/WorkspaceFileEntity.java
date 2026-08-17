package com.skillforge.studio.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作区文件索引。文件实体只记录元数据，真实字节由 StorageService 管理。
 */
@Data
@TableName("workspace_files")
public class WorkspaceFileEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String workspaceId;
    private String relativePath;
    private String storagePath;
    private String originalName;
    private String fileType;
    private String mimeType;
    private Long sizeBytes;
    private String sha256;
    private String sourceBlobSha;
    private String sourceSha256;
    private LocalDateTime createdAt;
}
