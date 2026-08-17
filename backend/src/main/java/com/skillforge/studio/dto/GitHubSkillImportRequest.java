package com.skillforge.studio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 用户确认 GitHub 导入时提交的仓库、分支和 skill 目录清单。 */
public record GitHubSkillImportRequest(
    @NotBlank @Size(max = 255) String repositoryFullName,
    @NotBlank @Size(max = 255) String branch,
    @NotBlank @Size(max = 120) String workspaceName,
    @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 500) String> skillPaths
) {
}
