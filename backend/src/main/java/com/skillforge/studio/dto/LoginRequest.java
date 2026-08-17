package com.skillforge.studio.dto;

import jakarta.validation.constraints.NotBlank;

/** 登录请求允许使用用户名或邮箱作为 account。 */
public record LoginRequest(
    @NotBlank(message = "账号不能为空") String account,
    @NotBlank(message = "密码不能为空") String password
) {
}
