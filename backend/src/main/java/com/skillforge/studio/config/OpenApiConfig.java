package com.skillforge.studio.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(title = "SkillForge Studio API", version = "v1", description = "AI skill 工作区管理平台接口"))
@SecurityScheme(name = "sessionCookie", type = SecuritySchemeType.APIKEY, in = io.swagger.v3.oas.annotations.enums.SecuritySchemeIn.COOKIE, paramName = "SESSION")
/**
 * Knife4j/OpenAPI 元数据配置，声明平台接口版本和基于 Session Cookie 的认证方式。
 */
public class OpenApiConfig {
}
