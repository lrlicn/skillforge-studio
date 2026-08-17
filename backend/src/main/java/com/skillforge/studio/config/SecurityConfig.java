package com.skillforge.studio.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@ConditionalOnProperty(prefix = "skillforge.github", name = "oauth-enabled", havingValue = "false", matchIfMissing = true)
/**
 * GitHub OAuth 关闭时使用的开发安全链。业务接口的用户归属仍由 HttpSession 和服务层校验。
 */
public class SecurityConfig {
    @Bean
    /** 首版账号认证由 AuthController 管理，后续会将会话校验收敛到统一过滤器。 */
    SecurityFilterChain securityFilterChain(HttpSecurity http, CookieCsrfTokenRepository csrfTokenRepository) throws Exception {
        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/system/health", "/api/v1/system/csrf", "/doc.html", "/webjars/**", "/v3/api-docs/**", "/swagger-resources/**").permitAll()
                .anyRequest().permitAll()
            );
        return http.build();
    }
}
