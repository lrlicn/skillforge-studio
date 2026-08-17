package com.skillforge.studio.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 图片资源扩展信息。宽高仅对可解析的位图保存，SVG 等格式允许为空。
 */
@Data
@TableName("local_assets")
public class LocalAssetEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String workspaceId;
    private Long fileId;
    private String relativePath;
    private String storagePath;
    private String mimeType;
    private Long sizeBytes;
    private Integer width;
    private Integer height;
    private String sha256;
    private LocalDateTime createdAt;
}
