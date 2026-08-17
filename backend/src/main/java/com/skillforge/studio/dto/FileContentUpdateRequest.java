package com.skillforge.studio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 文本保存请求携带读取时的 SHA-256，用于检测其他页面或进程已经修改文件的情况。
 */
public record FileContentUpdateRequest(
    @NotNull(message = "文件内容不能为空") @Size(max = 2_000_000, message = "文本内容不能超过 200 万个字符") String content,
    @NotBlank(message = "文件版本不能为空") @Size(min = 64, max = 64, message = "文件版本格式不正确") String expectedSha256
) {
}
