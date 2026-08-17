package com.skillforge.studio.security;

import com.skillforge.studio.config.GitHubOAuthProperties;
import com.skillforge.studio.service.GitHubConnectionService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Component
@ConditionalOnProperty(prefix = "skillforge.github", name = "oauth-enabled", havingValue = "true")
/** OAuth 回调成功后把 GitHub 身份绑定到授权前已经登录的平台账号，并持久化加密 Token。 */
public class GitHubOAuthSuccessHandler implements AuthenticationSuccessHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubOAuthSuccessHandler.class);

    private final CurrentUserProvider currentUserProvider;
    private final GitHubConnectionService connectionService;
    private final OAuth2AuthorizedClientRepository authorizedClientRepository;
    private final GitHubOAuthProperties properties;

    public GitHubOAuthSuccessHandler(
        CurrentUserProvider currentUserProvider,
        GitHubConnectionService connectionService,
        OAuth2AuthorizedClientRepository authorizedClientRepository,
        GitHubOAuthProperties properties
    ) {
        this.currentUserProvider = currentUserProvider;
        this.connectionService = connectionService;
        this.authorizedClientRepository = authorizedClientRepository;
        this.properties = properties;
    }

    @Override
    public void onAuthenticationSuccess(
        HttpServletRequest request,
        HttpServletResponse response,
        Authentication authentication
    ) throws IOException, ServletException {
        String errorCode = "binding_failed";
        try {
            Long platformUserId = currentUserProvider.requireUserId(request);
            if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)
                || !(authentication.getPrincipal() instanceof OAuth2User oauthUser)) {
                errorCode = "authentication_result_invalid";
                throw new IllegalStateException("GitHub OAuth 认证结果类型无效");
            }
            // 直接从处理本次回调的 Repository 读取，避免通过全局 Service 二次查询产生时序差异。
            OAuth2AuthorizedClient authorizedClient = authorizedClientRepository.loadAuthorizedClient(
                oauthToken.getAuthorizedClientRegistrationId(),
                oauthToken,
                request
            );
            if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
                errorCode = "authorized_client_missing";
                throw new IllegalStateException("未获取到 GitHub Access Token");
            }
            connectionService.connect(platformUserId, oauthUser, authorizedClient.getAccessToken());
            response.sendRedirect(redirectUri("connected", null));
        } catch (Exception exception) {
            if (exception instanceof ResponseStatusException statusException
                && statusException.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                errorCode = "platform_session_missing";
            }
            // 日志只记录异常类型和调用栈，不记录请求参数、Client Secret 或 Access Token。
            LOGGER.error("GitHub OAuth 回调后的平台账号绑定失败，错误码：{}", errorCode, exception);
            response.sendRedirect(redirectUri("error", errorCode));
        }
    }

    private String redirectUri(String result, String reason) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(properties.frontendRedirectUri())
            .queryParam("github", result);
        if (reason != null) {
            builder.queryParam("reason", reason);
        }
        return builder.build().toUriString();
    }
}
