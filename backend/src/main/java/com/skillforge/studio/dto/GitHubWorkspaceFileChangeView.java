package com.skillforge.studio.dto;

/** GitHub 工作区中单个受跟踪文件的变更结果，不暴露服务器存储路径。 */
public record GitHubWorkspaceFileChangeView(
    String path,
    String status,
    boolean localChanged,
    boolean remoteChanged,
    String localSha256,
    String baseBlobSha,
    String remoteBlobSha
) {
}
