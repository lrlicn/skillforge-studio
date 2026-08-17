package com.skillforge.studio.controller;

import com.skillforge.studio.dto.GitHubRepositoryView;
import com.skillforge.studio.dto.GitHubSkillImportRequest;
import com.skillforge.studio.dto.GitHubSkillView;
import com.skillforge.studio.dto.GitHubWorkspaceChangesView;
import com.skillforge.studio.dto.GitHubCommitRequest;
import com.skillforge.studio.dto.GitHubCommitResult;
import com.skillforge.studio.dto.GitHubFileDiffView;
import com.skillforge.studio.dto.WorkspaceImportResult;
import com.skillforge.studio.security.CurrentUserProvider;
import com.skillforge.studio.service.GitHubRepositoryService;
import com.skillforge.studio.service.GitHubWorkspaceChangeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/github")
@Tag(name = "GitHub 仓库")
/** 已连接 GitHub 的平台用户通过本控制器选择仓库、扫描并导入 skills。 */
public class GitHubRepositoryController {
    private final CurrentUserProvider currentUserProvider;
    private final GitHubRepositoryService repositoryService;
    private final GitHubWorkspaceChangeService workspaceChangeService;

    public GitHubRepositoryController(
        CurrentUserProvider currentUserProvider,
        GitHubRepositoryService repositoryService,
        GitHubWorkspaceChangeService workspaceChangeService
    ) {
        this.currentUserProvider = currentUserProvider;
        this.repositoryService = repositoryService;
        this.workspaceChangeService = workspaceChangeService;
    }

    @GetMapping("/repositories")
    @Operation(summary = "查询当前 GitHub 连接可访问的仓库")
    public List<GitHubRepositoryView> repositories(HttpServletRequest request) {
        return repositoryService.repositories(currentUserProvider.requireUserId(request));
    }

    @GetMapping("/skills")
    @Operation(summary = "扫描指定仓库分支中的 SKILL.md")
    public List<GitHubSkillView> skills(
        @RequestParam String repository,
        @RequestParam String ref,
        HttpServletRequest request
    ) {
        return repositoryService.skills(currentUserProvider.requireUserId(request), repository, ref);
    }

    @PostMapping("/import")
    @Operation(summary = "将选择的 GitHub skills 导入工作区")
    public WorkspaceImportResult importSkills(
        @Valid @RequestBody GitHubSkillImportRequest importRequest,
        HttpServletRequest request
    ) {
        return repositoryService.importSkills(currentUserProvider.requireUserId(request), importRequest);
    }

    @GetMapping("/workspaces/{workspaceId}/changes")
    @Operation(summary = "比较 GitHub 工作区与当前远端分支")
    public GitHubWorkspaceChangesView workspaceChanges(
        @org.springframework.web.bind.annotation.PathVariable String workspaceId,
        HttpServletRequest request
    ) {
        return workspaceChangeService.compare(currentUserProvider.requireUserId(request), workspaceId);
    }

    @PostMapping("/workspaces/{workspaceId}/commits")
    @Operation(summary = "提交并推送 GitHub 工作区的本地改动")
    public GitHubCommitResult commitWorkspace(
        @org.springframework.web.bind.annotation.PathVariable String workspaceId,
        @Valid @RequestBody GitHubCommitRequest commitRequest,
        HttpServletRequest request
    ) {
        return workspaceChangeService.commit(
            currentUserProvider.requireUserId(request), workspaceId, commitRequest.message()
        );
    }

    @GetMapping("/workspaces/{workspaceId}/changes/diff")
    @Operation(summary = "读取 GitHub 工作区单文件提交前 Diff")
    public GitHubFileDiffView workspaceFileDiff(
        @org.springframework.web.bind.annotation.PathVariable String workspaceId,
        @RequestParam String path,
        HttpServletRequest request
    ) {
        return workspaceChangeService.diff(currentUserProvider.requireUserId(request), workspaceId, path);
    }
}
