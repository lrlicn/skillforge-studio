package com.skillforge.studio.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.studio.config.GitHubOAuthProperties;
import com.skillforge.studio.dto.GitHubConnectionView;
import com.skillforge.studio.entity.GitHubConnectionEntity;
import com.skillforge.studio.mapper.GitHubConnectionMapper;
import com.skillforge.studio.security.GitHubTokenCipher;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;

@Service
/** GitHub 连接服务负责账号唯一绑定、令牌加密保存、状态查询和解除连接。 */
public class GitHubConnectionService {
    private final GitHubConnectionMapper connectionMapper;
    private final GitHubOAuthProperties properties;
    private final GitHubTokenCipher tokenCipher;

    public GitHubConnectionService(
        GitHubConnectionMapper connectionMapper,
        GitHubOAuthProperties properties,
        GitHubTokenCipher tokenCipher
    ) {
        this.connectionMapper = connectionMapper;
        this.properties = properties;
        this.tokenCipher = tokenCipher;
    }

    /** 查询连接状态时永远不解密 Token，也不会把任何密钥信息暴露给前端。 */
    public GitHubConnectionView status(Long userId) {
        GitHubConnectionEntity connection = findByUserId(userId);
        return connection == null
            ? GitHubConnectionView.disconnected(isAuthorizationAvailable())
            : GitHubConnectionView.from(connection, isAuthorizationAvailable());
    }

    /** 只有开关、客户端凭据和令牌加密密钥同时存在时，前端才允许发起授权。 */
    public boolean isAuthorizationAvailable() {
        return properties.oauthEnabled()
            && StringUtils.hasText(properties.clientId())
            && StringUtils.hasText(properties.clientSecret())
            && StringUtils.hasText(properties.tokenEncryptionKey());
    }

    public void requireAuthorizationAvailable() {
        if (!isAuthorizationAvailable()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub OAuth 尚未完成配置");
        }
    }

    /** GitHub API 调用前按当前平台账号读取并解密 Token；密文和明文都不会返回控制器或前端。 */
    public String requireAccessToken(Long userId) {
        GitHubConnectionEntity connection = findByUserId(userId);
        if (connection == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先连接 GitHub 账号");
        }
        if (connection.getTokenExpiresAt() != null && connection.getTokenExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "GitHub 授权已过期，请重新授权");
        }
        return tokenCipher.decrypt(connection.getAccessTokenEncrypted());
    }

    /** 提交前确认 OAuth 授权包含 GitHub 内容写权限，避免误用只读 Token。 */
    public String requireWriteAccessToken(Long userId) {
        GitHubConnectionEntity connection = findByUserId(userId);
        if (connection == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先连接 GitHub 账号");
        }
        String scopes = connection.getTokenScopes() == null ? "" : connection.getTokenScopes();
        boolean writable = java.util.Arrays.stream(scopes.split(","))
            .map(String::trim)
            .anyMatch(scope -> scope.equals("repo") || scope.equals("public_repo"));
        if (!writable) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前 GitHub 授权不包含仓库写权限，请重新授权");
        }
        return requireAccessToken(userId);
    }

    /**
     * OAuth 成功后以 GitHub 的不可变数字 ID 建立绑定；再次授权会更新资料、权限和 Token。
     * 数据库唯一约束之外再做业务检查，以便向用户返回清晰的冲突原因。
     */
    @Transactional
    public void connect(Long userId, OAuth2User principal, OAuth2AccessToken accessToken) {
        Map<String, Object> attributes = principal.getAttributes();
        Long githubUserId = requiredLong(attributes, "id");
        String login = requiredString(attributes, "login");
        GitHubConnectionEntity occupied = connectionMapper.selectOne(new LambdaQueryWrapper<GitHubConnectionEntity>()
            .eq(GitHubConnectionEntity::getGithubUserId, githubUserId)
            .last("LIMIT 1"));
        if (occupied != null && !occupied.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该 GitHub 账号已绑定其他平台账号");
        }

        GitHubConnectionEntity connection = findByUserId(userId);
        boolean creating = connection == null;
        if (creating) {
            connection = new GitHubConnectionEntity();
            connection.setUserId(userId);
        }
        connection.setGithubUserId(githubUserId);
        connection.setGithubLogin(login);
        connection.setDisplayName(optionalString(attributes.get("name")));
        connection.setAvatarUrl(optionalString(attributes.get("avatar_url")));
        connection.setAccessTokenEncrypted(tokenCipher.encrypt(accessToken.getTokenValue()));
        connection.setTokenScopes(accessToken.getScopes().stream().sorted(Comparator.naturalOrder()).reduce((left, right) -> left + "," + right).orElse(""));
        connection.setTokenExpiresAt(resolveTokenExpiration(accessToken));
        if (creating) {
            connectionMapper.insert(connection);
        } else {
            connectionMapper.updateById(connection);
        }
    }

    /** 解除连接会删除加密 Token；GitHub 端授权可由用户在 GitHub 设置中进一步撤销。 */
    @Transactional
    public void disconnect(Long userId) {
        connectionMapper.delete(new LambdaQueryWrapper<GitHubConnectionEntity>()
            .eq(GitHubConnectionEntity::getUserId, userId));
    }

    private GitHubConnectionEntity findByUserId(Long userId) {
        return connectionMapper.selectOne(new LambdaQueryWrapper<GitHubConnectionEntity>()
            .eq(GitHubConnectionEntity::getUserId, userId)
            .last("LIMIT 1"));
    }

    private Long requiredLong(Map<String, Object> attributes, String name) {
        Object value = attributes.get(name);
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                // 统一在下方返回远端资料不完整，避免暴露底层类型转换细节。
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub 返回的账号资料不完整");
    }

    private String requiredString(Map<String, Object> attributes, String name) {
        String value = optionalString(attributes.get(name));
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub 返回的账号资料不完整");
        }
        return value;
    }

    private String optionalString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * GitHub 未启用短期 Token 时不会返回 expires_in，Spring 会把 expiresAt 构造成 issuedAt 本身。
     * 只有明显晚于签发时间的过期点才持久化，否则以 NULL 表示远端未声明过期时间。
     */
    private LocalDateTime resolveTokenExpiration(OAuth2AccessToken accessToken) {
        Instant expiresAt = accessToken.getExpiresAt();
        Instant issuedAt = accessToken.getIssuedAt();
        if (expiresAt == null || (issuedAt != null && !expiresAt.isAfter(issuedAt.plusSeconds(60)))) {
            return null;
        }
        return LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault());
    }
}
