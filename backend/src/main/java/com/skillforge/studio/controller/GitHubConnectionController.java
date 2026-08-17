package com.skillforge.studio.controller;

import com.skillforge.studio.dto.GitHubAuthorizationView;
import com.skillforge.studio.dto.GitHubConnectionView;
import com.skillforge.studio.security.CurrentUserProvider;
import com.skillforge.studio.service.GitHubConnectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/connections/github")
@Tag(name = "GitHub 连接")
/** GitHub 授权与平台账号登录相互独立，本控制器只允许已登录平台账号管理自己的连接。 */
public class GitHubConnectionController {
    private final CurrentUserProvider currentUserProvider;
    private final GitHubConnectionService connectionService;

    public GitHubConnectionController(CurrentUserProvider currentUserProvider, GitHubConnectionService connectionService) {
        this.currentUserProvider = currentUserProvider;
        this.connectionService = connectionService;
    }

    @GetMapping
    @Operation(summary = "查询当前账号的 GitHub 连接状态")
    public GitHubConnectionView status(HttpServletRequest request) {
        return connectionService.status(currentUserProvider.requireUserId(request));
    }

    @PostMapping("/authorize")
    @Operation(summary = "获取 GitHub OAuth 授权入口")
    public GitHubAuthorizationView authorize(HttpServletRequest request) {
        currentUserProvider.requireUserId(request);
        connectionService.requireAuthorizationAvailable();
        return new GitHubAuthorizationView("/oauth2/authorization/github");
    }

    @DeleteMapping
    @Operation(summary = "解除当前账号的 GitHub 连接")
    public void disconnect(HttpServletRequest request) {
        connectionService.disconnect(currentUserProvider.requireUserId(request));
    }
}
