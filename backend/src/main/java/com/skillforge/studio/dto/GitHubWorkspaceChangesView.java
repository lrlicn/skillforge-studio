package com.skillforge.studio.dto;

import java.util.List;

/** GitHub 工作区与远端分支的只读比较摘要。 */
public record GitHubWorkspaceChangesView(
    String workspaceId,
    String repositoryFullName,
    String repositoryRef,
    String baseCommitSha,
    String remoteCommitSha,
    boolean baselineAvailable,
    boolean remoteAdvanced,
    int trackedFileCount,
    int localChangeCount,
    int remoteChangeCount,
    int conflictCount,
    List<GitHubWorkspaceFileChangeView> changes
) {
}
