package com.skillforge.studio.dto;

import java.util.List;

/** GitHub 提交成功后的可追踪结果。 */
public record GitHubCommitResult(
    String workspaceId,
    String repositoryFullName,
    String repositoryRef,
    String commitSha,
    String commitUrl,
    List<String> paths,
    int changedFileCount
) {
}
