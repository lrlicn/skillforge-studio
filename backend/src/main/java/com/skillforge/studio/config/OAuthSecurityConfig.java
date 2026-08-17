package com.skillforge.studio.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.skillforge.studio.security.GitHubOAuthFailureHandler;
import com.skillforge.studio.security.GitHubOAuthSuccessHandler;
import com.skillforge.studio.security.GitHubTokenCipher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

@Configuration
@ConditionalOnProperty(prefix = "skillforge.github", name = "oauth-enabled", havingValue = "true")
/**
 * GitHub OAuth 开启时加载的安全链。该类同时负责构造 GitHub 客户端注册和保护业务接口。
 */
public class OAuthSecurityConfig {
    /**
     * 使用私密配置构造 GitHub 客户端；开启开关但缺少凭据时立即失败，避免产生半可用状态。
     */
    @Bean
    ClientRegistrationRepository clientRegistrationRepository(
        GitHubOAuthProperties properties,
        GitHubTokenCipher tokenCipher
    ) {
        if (!StringUtils.hasText(properties.clientId()) || !StringUtils.hasText(properties.clientSecret())) {
            throw new IllegalStateException("启用 GitHub OAuth 时必须配置 client-id 和 client-secret");
        }
        tokenCipher.validateConfiguration();

        ClientRegistration registration = ClientRegistration.withRegistrationId("github")
            .clientId(properties.clientId())
            .clientSecret(properties.clientSecret())
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(properties.redirectUri())
            .scope(properties.scopes().toArray(String[]::new))
            .authorizationUri("https://github.com/login/oauth/authorize")
            .tokenUri("https://github.com/login/oauth/access_token")
            .userInfoUri("https://api.github.com/user")
            .userNameAttributeName("id")
            .clientName("GitHub")
            .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean
    /**
     * 平台账号继续由 HttpSession 和 CurrentUserProvider 认证，Spring Security 只承载 GitHub OAuth 握手。
     * 业务接口不能改用 oauth2Login 的认证状态，否则会把“绑定 GitHub”错误地变成平台登录前提。
     */
    SecurityFilterChain oauthSecurityFilterChain(
        HttpSecurity http,
        GitHubOAuthSuccessHandler successHandler,
        GitHubOAuthFailureHandler failureHandler,
        CookieCsrfTokenRepository csrfTokenRepository
    ) throws Exception {
        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/system/health", "/api/v1/system/csrf").permitAll()
                .anyRequest().permitAll())
            .oauth2Login(oauth -> oauth
                .successHandler(successHandler)
                .failureHandler(failureHandler)
            );
        return http.build();
    }
}
