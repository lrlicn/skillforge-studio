package com.skillforge.studio.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GitHubOAuthProperties.class)
/** 无论 OAuth 开关是否启用都加载 GitHub 配置模型，使连接状态接口能够返回真实可用性。 */
public class GitHubConfiguration {
}
