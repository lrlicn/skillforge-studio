package com.skillforge.studio.dto;

/** 提交前单文件差异内容，基线代表修改前，local 代表当前工作区。 */
public record GitHubFileDiffView(
    String path,
    String status,
    boolean binary,
    boolean truncated,
    String baseContent,
    String localContent,
    String remoteContent,
    String baseSha,
    String localSha,
    String remoteSha
) {
}
