package com.skillforge.studio.dto;

import java.util.List;

/**
 * 文件树节点。目录节点没有 fileId，文件节点通过 fileId 请求内容。
 */
public record WorkspaceTreeNode(
    String key,
    Long fileId,
    String name,
    String path,
    String nodeType,
    String mimeType,
    Long sizeBytes,
    String sha256,
    List<WorkspaceTreeNode> children
) {
}
