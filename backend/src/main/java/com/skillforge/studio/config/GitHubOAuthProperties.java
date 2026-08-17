package com.skillforge.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "skillforge.github")
/**
 * GitHub OAuth 单一配置模型。oauthEnabled 是唯一开关，其余字段仅在开关开启时使用。
 */
public record GitHubOAuthProperties(
    boolean oauthEnabled,
    String clientId,
    String clientSecret,
    String tokenEncryptionKey,
    List<String> scopes,
    String redirectUri,
    String frontendRedirectUri
) {
    /** 为权限范围和前后端回调地址提供稳定默认值，避免在多个配置类中重复声明。 */
    public GitHubOAuthProperties {
        scopes = scopes == null || scopes.isEmpty() ? List.of("read:user", "user:email", "repo") : List.copyOf(scopes);
        redirectUri = redirectUri == null || redirectUri.isBlank()
            ? "{baseUrl}/login/oauth2/code/{registrationId}"
            : redirectUri;
        frontendRedirectUri = frontendRedirectUri == null || frontendRedirectUri.isBlank()
            ? "http://127.0.0.1:5173/"
            : frontendRedirectUri;
    }
}
