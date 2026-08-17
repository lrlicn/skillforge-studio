package com.skillforge.studio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** GitHub 提交请求，只允许用户显式提供提交说明。 */
public record GitHubCommitRequest(
    @NotBlank(message = "提交说明不能为空")
    @Size(max = 200, message = "提交说明不能超过 200 个字符")
    String message
) {
}
