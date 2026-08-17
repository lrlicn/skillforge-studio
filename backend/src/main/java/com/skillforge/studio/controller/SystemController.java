package com.skillforge.studio.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;

@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "系统")
/** 提供不依赖登录状态的基础运行检查接口。 */
public class SystemController {
    @GetMapping("/health")
    @Operation(summary = "检查服务状态")
    /** 返回服务标识和服务器当前时间，用于前端及部署探针确认后端可用。 */
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "skillforge-studio-backend", "time", OffsetDateTime.now().toString());
    }

    @GetMapping("/csrf")
    @Operation(summary = "获取浏览器写请求所需的 CSRF Token")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }
}
