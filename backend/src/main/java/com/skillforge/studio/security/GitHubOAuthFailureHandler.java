package com.skillforge.studio.security;

import com.skillforge.studio.config.GitHubOAuthProperties;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Component
@ConditionalOnProperty(prefix = "skillforge.github", name = "oauth-enabled", havingValue = "true")
/** OAuth 握手失败处理器记录安全错误码，并把用户带回连接管理页。 */
public class GitHubOAuthFailureHandler implements AuthenticationFailureHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubOAuthFailureHandler.class);

    private final GitHubOAuthProperties properties;

    public GitHubOAuthFailureHandler(GitHubOAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public void onAuthenticationFailure(
        HttpServletRequest request,
        HttpServletResponse response,
        AuthenticationException exception
    ) throws IOException, ServletException {
        String springErrorCode = exception instanceof OAuth2AuthenticationException oauthException
            ? oauthException.getError().getErrorCode()
            : "oauth_authentication_failed";
        String safeErrorCode = toSafeErrorCode(springErrorCode);
        // 原始 Spring 错误码只写入服务端日志，前端仅接收有限的安全错误码。
        LOGGER.error("GitHub OAuth 握手失败，Spring 错误码：{}，安全错误码：{}", springErrorCode, safeErrorCode, exception);
        String redirectUri = UriComponentsBuilder.fromUriString(properties.frontendRedirectUri())
            .queryParam("github", "error")
            .queryParam("reason", safeErrorCode)
            .build()
            .toUriString();
        response.sendRedirect(redirectUri);
    }

    private String toSafeErrorCode(String springErrorCode) {
        return switch (springErrorCode) {
            case "authorization_request_not_found" -> "oauth_session_missing";
            case "invalid_token_response" -> "token_exchange_failed";
            case "user_info_retrieval_error" -> "user_info_failed";
            default -> "oauth_failed";
        };
    }
}
