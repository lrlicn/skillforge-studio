package com.skillforge.studio.dto;

import java.time.OffsetDateTime;

/** GitHub 仓库选择页需要的精简信息，不透传 GitHub API 的多余字段。 */
public record GitHubRepositoryView(
    long id,
    String name,
    String fullName,
    String owner,
    boolean privateRepository,
    String defaultBranch,
    String description,
    String htmlUrl,
    OffsetDateTime updatedAt
) {
}
