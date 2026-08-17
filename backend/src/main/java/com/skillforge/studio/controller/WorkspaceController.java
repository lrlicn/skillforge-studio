package com.skillforge.studio.controller;

import com.skillforge.studio.dto.WorkspaceImportResult;
import com.skillforge.studio.dto.FileContentUpdateRequest;
import com.skillforge.studio.dto.FileContentUpdateResult;
import com.skillforge.studio.dto.WorkspaceRenameRequest;
import com.skillforge.studio.dto.WorkspaceSummary;
import com.skillforge.studio.dto.WorkspaceTreeNode;
import com.skillforge.studio.security.CurrentUserProvider;
import com.skillforge.studio.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 工作区 HTTP 接口。所有操作先从会话解析当前用户，再调用服务层执行归属校验。
 */
@RestController
@RequestMapping("/api/v1/workspaces")
@Tag(name = "工作区")
public class WorkspaceController {
    private static final List<String> SAFE_INLINE_IMAGE_TYPES = List.of(
        "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp"
    );
    private static final List<String> SAFE_INLINE_TEXT_TYPES = List.of("text/plain", "text/markdown");
    private final WorkspaceService workspaceService;
    private final CurrentUserProvider currentUserProvider;

    public WorkspaceController(WorkspaceService workspaceService, CurrentUserProvider currentUserProvider) {
        this.workspaceService = workspaceService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * relativePaths 与 files 使用相同顺序，目录结构由浏览器选择结果显式传入。
     */
    @PostMapping(value = "/import-local", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "导入用户主动选择的本地文件")
    public WorkspaceImportResult importLocal(
        @Parameter(description = "工作区名称") @RequestPart("workspaceName") @NotBlank String workspaceName,
        @Parameter(description = "用户主动选择的文件") @RequestPart("files") MultipartFile[] files,
        @Parameter(description = "与文件顺序一致的相对路径") @RequestParam("relativePaths") List<String> relativePaths,
        HttpServletRequest request
    ) {
        Long userId = currentUserProvider.requireUserId(request);
        return workspaceService.importLocal(userId, workspaceName, files, relativePaths);
    }

    @GetMapping
    @Operation(summary = "查询当前用户的工作区")
    public List<WorkspaceSummary> list(HttpServletRequest request) {
        return workspaceService.list(currentUserProvider.requireUserId(request));
    }

    @PutMapping("/{workspaceId}")
    @Operation(summary = "重命名工作区")
    public WorkspaceSummary rename(
        @PathVariable String workspaceId,
        @Valid @RequestBody WorkspaceRenameRequest renameRequest,
        HttpServletRequest request
    ) {
        return workspaceService.rename(currentUserProvider.requireUserId(request), workspaceId, renameRequest.name());
    }

    @DeleteMapping("/{workspaceId}")
    @Operation(summary = "删除工作区")
    public void delete(@PathVariable String workspaceId, HttpServletRequest request) {
        workspaceService.delete(currentUserProvider.requireUserId(request), workspaceId);
    }

    @GetMapping("/{workspaceId}/tree")
    @Operation(summary = "查询工作区文件树")
    public List<WorkspaceTreeNode> tree(@PathVariable String workspaceId, HttpServletRequest request) {
        return workspaceService.tree(currentUserProvider.requireUserId(request), workspaceId);
    }

    /**
     * 文件内容以内联方式返回，服务端根据数据库元数据决定 MIME，不接受前端指定磁盘路径。
     */
    @GetMapping("/{workspaceId}/files/{fileId}/content")
    @Operation(summary = "读取工作区文件内容")
    public ResponseEntity<Resource> content(
        @PathVariable String workspaceId,
        @PathVariable Long fileId,
        HttpServletRequest request
    ) {
        WorkspaceService.WorkspaceFileResource result = workspaceService.loadFile(
            currentUserProvider.requireUserId(request),
            workspaceId,
            fileId
        );
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(result.file().getMimeType());
        } catch (IllegalArgumentException exception) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        // HTML、SVG 等主动内容只允许下载；常见位图和文本可安全内联预览。
        String normalizedMediaType = mediaType.toString().toLowerCase();
        boolean safeInline = SAFE_INLINE_TEXT_TYPES.contains(normalizedMediaType)
            || SAFE_INLINE_IMAGE_TYPES.contains(normalizedMediaType);
        ContentDisposition disposition = (safeInline ? ContentDisposition.inline() : ContentDisposition.attachment())
            .filename(result.file().getOriginalName(), StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .contentType(mediaType)
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("Content-Security-Policy", "sandbox; default-src 'none'")
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .body(result.resource());
    }

    @PutMapping(value = "/{workspaceId}/files/{fileId}/content", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "保存工作区文本文件")
    public FileContentUpdateResult updateContent(
        @PathVariable String workspaceId,
        @PathVariable Long fileId,
        @Valid @RequestBody FileContentUpdateRequest updateRequest,
        HttpServletRequest request
    ) {
        return workspaceService.updateTextFile(
            currentUserProvider.requireUserId(request),
            workspaceId,
            fileId,
            updateRequest.content(),
            updateRequest.expectedSha256()
        );
    }
}
