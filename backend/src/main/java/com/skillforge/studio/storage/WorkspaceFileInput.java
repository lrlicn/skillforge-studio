package com.skillforge.studio.storage;

/** GitHub 等非浏览器来源写入工作区时使用的内存文件模型，并保留远端对象基线。 */
public record WorkspaceFileInput(
    String relativePath,
    byte[] content,
    String contentType,
    String sourceBlobSha
) {
}
