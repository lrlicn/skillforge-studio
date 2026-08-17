package com.skillforge.studio.dto;

/** 在仓库树中发现的 skill，以 SKILL.md 所在目录作为可导入边界。 */
public record GitHubSkillView(
    String name,
    String directoryPath,
    String manifestPath
) {
}
