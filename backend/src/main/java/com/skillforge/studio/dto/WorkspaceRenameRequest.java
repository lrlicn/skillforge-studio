package com.skillforge.studio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 工作区重命名请求只允许修改显示名称，不允许更改来源和磁盘目录。 */
public record WorkspaceRenameRequest(
    @NotBlank(message = "工作区名称不能为空") @Size(max = 120, message = "工作区名称不能超过 120 个字符") String name
) {
}
