package com.skillforge.studio.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 注册请求在进入服务层前完成格式和长度校验。 */
public record RegisterRequest(
    @NotBlank(message = "用户名不能为空") @Size(min = 2, max = 40, message = "用户名长度应为 2-40 个字符") String username,
    @NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确") String email,
    @NotBlank(message = "密码不能为空") @Size(min = 8, max = 72, message = "密码长度应为 8-72 个字符") String password
) {
}
