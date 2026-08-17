package com.skillforge.studio.dto;

import com.skillforge.studio.entity.GitHubConnectionEntity;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/** 连接管理页使用的安全视图，刻意不包含访问令牌和密文。 */
public record GitHubConnectionView(
    boolean authorizationAvailable,
    boolean connected,
    Long githubUserId,
    String login,
    String displayName,
    String avatarUrl,
    List<String> scopes,
    LocalDateTime connectedAt,
    LocalDateTime updatedAt
) {
    /** OAuth 未配置或当前用户尚未连接时返回稳定的空状态。 */
    public static GitHubConnectionView disconnected(boolean authorizationAvailable) {
        return new GitHubConnectionView(
            authorizationAvailable, false, null, null, null, null, List.of(), null, null
        );
    }

    /** 将数据库实体转换为前端视图，并过滤空权限项。 */
    public static GitHubConnectionView from(GitHubConnectionEntity entity, boolean authorizationAvailable) {
        List<String> scopes = entity.getTokenScopes() == null || entity.getTokenScopes().isBlank()
            ? List.of()
            : Arrays.stream(entity.getTokenScopes().split(","))
                .map(String::trim)
                .filter(scope -> !scope.isEmpty())
                .toList();
        return new GitHubConnectionView(
            authorizationAvailable,
            true,
            entity.getGithubUserId(),
            entity.getGithubLogin(),
            entity.getDisplayName(),
            entity.getAvatarUrl(),
            scopes,
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
