package com.skillforge.studio.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.studio.dto.GitHubWorkspaceFileChangeView;
import com.skillforge.studio.dto.GitHubWorkspaceChangesView;
import com.skillforge.studio.entity.WorkspaceEntity;
import com.skillforge.studio.entity.WorkspaceFileEntity;
import com.skillforge.studio.mapper.WorkspaceFileMapper;
import com.skillforge.studio.mapper.WorkspaceMapper;
import com.skillforge.studio.storage.StorageService;
import com.skillforge.studio.dto.GitHubCommitResult;
import com.skillforge.studio.dto.GitHubFileDiffView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 比较 GitHub 工作区的本地文件、导入基线和当前远端树。
 * 本服务只读，不更新基线，也不会创建提交或修改远端分支。
 */
@Service
public class GitHubWorkspaceChangeService {
    private static final Pattern REPOSITORY_NAME = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final long MAX_COMPARE_BLOB_BYTES = 20L * 1024 * 1024;
    private static final long MAX_COMMIT_BYTES = 100L * 1024 * 1024;
    private static final int MAX_COMMIT_FILES = 500;
    private static final Pattern BRANCH_NAME = Pattern.compile("^[A-Za-z0-9._/-]{1,255}$");

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceFileMapper workspaceFileMapper;
    private final GitHubConnectionService connectionService;
    private final GitHubApiClient apiClient;
    private final StorageService storageService;

    public GitHubWorkspaceChangeService(
        WorkspaceMapper workspaceMapper,
        WorkspaceFileMapper workspaceFileMapper,
        GitHubConnectionService connectionService,
        GitHubApiClient apiClient,
        StorageService storageService
    ) {
        this.workspaceMapper = workspaceMapper;
        this.workspaceFileMapper = workspaceFileMapper;
        this.connectionService = connectionService;
        this.apiClient = apiClient;
        this.storageService = storageService;
    }

    /**
     * 新工作区按导入基线执行三方比较；旧工作区缺少基线时，与当前远端内容进行兼容比较。
     * 返回列表只包含发生变化的文件，摘要中的 trackedFileCount 仍表示全部受跟踪文件数量。
     */
    public GitHubWorkspaceChangesView compare(Long userId, String workspaceId) {
        WorkspaceEntity workspace = requireGitHubWorkspace(userId, workspaceId);
        RepositoryCoordinates repository = parseRepository(workspace.getRepositoryFullName());
        validateBranch(workspace.getRepositoryRef());
        String accessToken = connectionService.requireAccessToken(userId);
        GitHubApiClient.CommitResponse remoteCommit = requireCommit(
            accessToken, repository, workspace.getRepositoryRef()
        );
        GitHubApiClient.TreeResponse remoteTree = apiClient.repositoryTree(
            accessToken, repository.owner(), repository.repository(), remoteCommit.commit().tree().sha()
        );
        if (remoteTree == null || remoteTree.tree() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub 未返回仓库文件树");
        }
        if (remoteTree.truncated()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "远端仓库树过大，无法安全比较变更");
        }

        Map<String, GitHubApiClient.TreeItemResponse> remoteFiles = remoteTree.tree().stream()
            .filter(item -> "blob".equals(item.type()) && item.path() != null)
            .collect(Collectors.toMap(
                item -> item.path().toLowerCase(Locale.ROOT),
                Function.identity(),
                (left, right) -> left
            ));
        List<WorkspaceFileEntity> localFiles = workspaceFileMapper.selectList(
            new LambdaQueryWrapper<WorkspaceFileEntity>()
                .eq(WorkspaceFileEntity::getWorkspaceId, workspaceId)
                .orderByAsc(WorkspaceFileEntity::getRelativePath)
        );
        boolean baselineAvailable = workspace.getRepositoryBaseCommitSha() != null
            && !workspace.getRepositoryBaseCommitSha().isBlank()
            && localFiles.stream().allMatch(this::hasFileBaseline);

        List<ComparedFile> comparedFiles = localFiles.stream()
            .map(file -> compareFile(accessToken, repository, file, remoteFiles, baselineAvailable))
            .toList();
        List<GitHubWorkspaceFileChangeView> changes = comparedFiles.stream()
            .filter(change -> !"UNCHANGED".equals(change.view().status()))
            .map(ComparedFile::view)
            .sorted(Comparator.comparing(GitHubWorkspaceFileChangeView::path))
            .toList();

        int localChangeCount = (int) comparedFiles.stream().filter(ComparedFile::localChanged).count();
        int remoteChangeCount = (int) comparedFiles.stream().filter(ComparedFile::remoteChanged).count();
        int conflictCount = (int) comparedFiles.stream()
            .filter(change -> "CONFLICT".equals(change.view().status()))
            .count();
        String baseCommitSha = workspace.getRepositoryBaseCommitSha();
        boolean remoteAdvanced = baseCommitSha != null && !baseCommitSha.equalsIgnoreCase(remoteCommit.sha());

        return new GitHubWorkspaceChangesView(
            workspaceId,
            workspace.getRepositoryFullName(),
            workspace.getRepositoryRef(),
            baseCommitSha,
            remoteCommit.sha(),
            baselineAvailable,
            remoteAdvanced,
            localFiles.size(),
            localChangeCount,
            remoteChangeCount,
            conflictCount,
            changes
        );
    }

    /** 提交本地改动，并在推送成功后更新工作区基线。 */
    @Transactional
    public GitHubCommitResult commit(Long userId, String workspaceId, String message) {
        WorkspaceEntity workspace = requireGitHubWorkspace(userId, workspaceId);
        String cleanMessage = message == null ? "" : message.trim();
        if (cleanMessage.isEmpty() || cleanMessage.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提交说明长度应为 1-200 个字符");
        }
        if (workspace.getRepositoryBaseCommitSha() == null || workspace.getRepositoryBaseCommitSha().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前工作区缺少提交基线，请重新导入 GitHub 工作区");
        }

        RepositoryCoordinates repository = parseRepository(workspace.getRepositoryFullName());
        validateBranch(workspace.getRepositoryRef());
        String accessToken = connectionService.requireWriteAccessToken(userId);
        GitHubApiClient.CommitResponse remoteCommit = requireCommit(accessToken, repository, workspace.getRepositoryRef());
        if (!workspace.getRepositoryBaseCommitSha().equalsIgnoreCase(remoteCommit.sha())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "远端分支已有新提交，请先重新比较并处理变更");
        }
        GitHubApiClient.TreeResponse remoteTree = apiClient.repositoryTree(
            accessToken, repository.owner(), repository.repository(), remoteCommit.commit().tree().sha()
        );
        if (remoteTree == null || remoteTree.tree() == null || remoteTree.truncated()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "远端文件树过大，无法安全提交");
        }
        Map<String, GitHubApiClient.TreeItemResponse> remoteFiles = remoteTree.tree().stream()
            .filter(item -> "blob".equals(item.type()) && item.path() != null)
            .collect(Collectors.toMap(item -> item.path().toLowerCase(Locale.ROOT), Function.identity(), (left, right) -> left));
        List<WorkspaceFileEntity> localFiles = workspaceFileMapper.selectList(new LambdaQueryWrapper<WorkspaceFileEntity>()
            .eq(WorkspaceFileEntity::getWorkspaceId, workspaceId)
            .orderByAsc(WorkspaceFileEntity::getRelativePath));
        List<WorkspaceFileEntity> changedFiles = localFiles.stream()
            .filter(file -> !equalsHash(file.getSha256(), file.getSourceSha256()))
            .toList();
        if (changedFiles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前工作区没有待提交的本地改动");
        }
        if (changedFiles.size() > MAX_COMMIT_FILES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "单次提交最多包含 500 个文件");
        }

        List<GitHubApiClient.TreeCreateEntry> treeEntries = new ArrayList<>();
        Map<WorkspaceFileEntity, String> createdBlobShas = new LinkedHashMap<>();
        long totalBytes = 0L;
        for (WorkspaceFileEntity file : changedFiles) {
            validateRelativePath(file.getRelativePath());
            GitHubApiClient.TreeItemResponse remoteFile = remoteFiles.get(file.getRelativePath().toLowerCase(Locale.ROOT));
            if (remoteFile == null || !equalsHash(remoteFile.sha(), file.getSourceBlobSha())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "远端文件已变化，请先重新比较并处理冲突: " + file.getRelativePath());
            }
            byte[] bytes;
            try {
                bytes = storageService.load(file.getStoragePath()).getContentAsByteArray();
            } catch (java.io.IOException exception) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取待提交文件失败: " + file.getRelativePath(), exception);
            }
            if (bytes.length > MAX_COMPARE_BLOB_BYTES || (totalBytes += bytes.length) > MAX_COMMIT_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "单次提交文件总大小不能超过 100 MB，单文件不能超过 20 MB");
            }
            if (!equalsHash(file.getSha256(), sha256(bytes))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "本地文件内容已脱离索引，请重新加载后再提交: " + file.getRelativePath());
            }
            String blobSha = apiClient.createBlob(
                accessToken, repository.owner(), repository.repository(), Base64.getEncoder().encodeToString(bytes)
            );
            createdBlobShas.put(file, blobSha);
            treeEntries.add(new GitHubApiClient.TreeCreateEntry(file.getRelativePath(), "100644", "blob", blobSha));
        }

        String treeSha = apiClient.createTree(
            accessToken, repository.owner(), repository.repository(), remoteCommit.commit().tree().sha(), treeEntries
        );
        GitHubApiClient.CommitCreateResponse createdCommit = apiClient.createCommit(
            accessToken, repository.owner(), repository.repository(), cleanMessage, treeSha, remoteCommit.sha()
        );
        if (createdCommit == null || createdCommit.sha() == null || createdCommit.sha().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub 未返回有效的提交");
        }
        apiClient.updateBranchRef(
            accessToken, repository.owner(), repository.repository(), workspace.getRepositoryRef(), createdCommit.sha()
        );

        workspace.setRepositoryBaseCommitSha(createdCommit.sha());
        workspaceMapper.updateById(workspace);
        createdBlobShas.forEach((file, blobSha) -> {
            file.setSourceBlobSha(blobSha);
            file.setSourceSha256(file.getSha256());
            workspaceFileMapper.updateById(file);
        });
        return new GitHubCommitResult(
            workspaceId, workspace.getRepositoryFullName(), workspace.getRepositoryRef(), createdCommit.sha(),
            createdCommit.htmlUrl(), changedFiles.stream().map(WorkspaceFileEntity::getRelativePath).toList(), changedFiles.size()
        );
    }

    /** 返回导入基线、本地当前内容和远端当前内容，供提交前全屏 Diff 使用。 */
    public GitHubFileDiffView diff(Long userId, String workspaceId, String path) {
        WorkspaceEntity workspace = requireGitHubWorkspace(userId, workspaceId);
        validateBranch(workspace.getRepositoryRef());
        validateRelativePath(path);
        WorkspaceFileEntity localFile = workspaceFileMapper.selectOne(new LambdaQueryWrapper<WorkspaceFileEntity>()
            .eq(WorkspaceFileEntity::getWorkspaceId, workspaceId)
            .eq(WorkspaceFileEntity::getRelativePath, path));
        if (localFile == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工作区文件不存在");
        }
        String accessToken = connectionService.requireAccessToken(userId);
        RepositoryCoordinates repository = parseRepository(workspace.getRepositoryFullName());
        GitHubApiClient.CommitResponse remoteCommit = requireCommit(accessToken, repository, workspace.getRepositoryRef());
        GitHubApiClient.TreeResponse remoteTree = apiClient.repositoryTree(
            accessToken, repository.owner(), repository.repository(), remoteCommit.commit().tree().sha()
        );
        if (remoteTree == null || remoteTree.tree() == null || remoteTree.truncated()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "远端文件树过大或不可用，无法生成 Diff");
        }
        GitHubApiClient.TreeItemResponse remoteFile = remoteTree.tree().stream()
            .filter(item -> "blob".equals(item.type()) && path.equals(item.path()))
            .findFirst().orElse(null);
        byte[] localBytes = readStoredBytes(localFile);
        byte[] baseBytes = localFile.getSourceBlobSha() == null ? null
            : apiClient.blob(accessToken, repository.owner(), repository.repository(), localFile.getSourceBlobSha());
        byte[] remoteBytes = remoteFile == null ? null
            : apiClient.blob(accessToken, repository.owner(), repository.repository(), remoteFile.sha());
        boolean binary = !isText(localFile, localBytes) || (baseBytes != null && !isText(localFile, baseBytes))
            || (remoteBytes != null && !isText(localFile, remoteBytes));
        boolean truncated = (localBytes.length > MAX_COMPARE_BLOB_BYTES)
            || (baseBytes != null && baseBytes.length > MAX_COMPARE_BLOB_BYTES)
            || (remoteBytes != null && remoteBytes.length > MAX_COMPARE_BLOB_BYTES);
        if (binary || truncated) {
            return new GitHubFileDiffView(path, "FILE", binary, truncated, null, null, null,
                localFile.getSourceBlobSha(), localFile.getSha256(), remoteFile == null ? null : remoteFile.sha());
        }
        return new GitHubFileDiffView(path, "FILE", false, false,
            decodeText(baseBytes), decodeText(localBytes), decodeText(remoteBytes),
            localFile.getSourceBlobSha(), localFile.getSha256(), remoteFile == null ? null : remoteFile.sha());
    }

    private byte[] readStoredBytes(WorkspaceFileEntity file) {
        try {
            return storageService.load(file.getStoragePath()).getContentAsByteArray();
        } catch (java.io.IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取本地文件失败", exception);
        }
    }

    private boolean isText(WorkspaceFileEntity file, byte[] bytes) {
        if (bytes.length == 0) return true;
        if (file.getMimeType() != null && file.getMimeType().startsWith("text/")) return true;
        String name = file.getOriginalName().toLowerCase(Locale.ROOT);
        boolean knownText = List.of("md", "markdown", "txt", "html", "htm", "css", "js", "jsx", "ts", "tsx", "json", "xml", "yaml", "yml", "toml", "properties", "py", "java", "sql", "sh").stream().anyMatch(ext -> name.endsWith("." + ext));
        if (!knownText) return false;
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private String decodeText(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private ComparedFile compareFile(
        String accessToken,
        RepositoryCoordinates repository,
        WorkspaceFileEntity localFile,
        Map<String, GitHubApiClient.TreeItemResponse> remoteFiles,
        boolean baselineAvailable
    ) {
        GitHubApiClient.TreeItemResponse remoteFile = remoteFiles.get(localFile.getRelativePath().toLowerCase(Locale.ROOT));
        if (!baselineAvailable) {
            return compareWithoutBaseline(accessToken, repository, localFile, remoteFile);
        }

        boolean localChanged = !equalsHash(localFile.getSha256(), localFile.getSourceSha256());
        boolean remoteChanged = remoteFile == null || !equalsHash(remoteFile.sha(), localFile.getSourceBlobSha());
        if (localChanged && remoteChanged && remoteFile != null && sameRemoteContent(accessToken, repository, localFile, remoteFile)) {
            localChanged = false;
            remoteChanged = false;
        }

        String status;
        if (!localChanged && !remoteChanged) status = "UNCHANGED";
        else if (localChanged && remoteChanged) status = "CONFLICT";
        else if (localChanged) status = "LOCAL_MODIFIED";
        else status = remoteFile == null ? "REMOTE_DELETED" : "REMOTE_MODIFIED";
        return compared(localFile, remoteFile, status, localChanged, remoteChanged);
    }

    /** 旧工作区无法判断变化方向，只能判断本地副本是否与当前远端一致。 */
    private ComparedFile compareWithoutBaseline(
        String accessToken,
        RepositoryCoordinates repository,
        WorkspaceFileEntity localFile,
        GitHubApiClient.TreeItemResponse remoteFile
    ) {
        if (remoteFile == null) {
            return compared(localFile, null, "LOCAL_MODIFIED", true, true);
        }
        boolean sameContent = sameRemoteContent(accessToken, repository, localFile, remoteFile);
        return compared(
            localFile,
            remoteFile,
            sameContent ? "UNCHANGED" : "LOCAL_MODIFIED",
            !sameContent,
            false
        );
    }

    private ComparedFile compared(
        WorkspaceFileEntity localFile,
        GitHubApiClient.TreeItemResponse remoteFile,
        String status,
        boolean localChanged,
        boolean remoteChanged
    ) {
        return new ComparedFile(
            new GitHubWorkspaceFileChangeView(
                localFile.getRelativePath(),
                status,
                localChanged,
                remoteChanged,
                localFile.getSha256(),
                localFile.getSourceBlobSha(),
                remoteFile == null ? null : remoteFile.sha()
            ),
            localChanged,
            remoteChanged
        );
    }

    /** 仅在必须确认内容是否收敛时下载 Blob，普通三方比较只使用对象 SHA。 */
    private boolean sameRemoteContent(
        String accessToken,
        RepositoryCoordinates repository,
        WorkspaceFileEntity localFile,
        GitHubApiClient.TreeItemResponse remoteFile
    ) {
        if (remoteFile.size() != null && remoteFile.size() > MAX_COMPARE_BLOB_BYTES) {
            return false;
        }
        byte[] remoteContent = apiClient.blob(
            accessToken, repository.owner(), repository.repository(), remoteFile.sha()
        );
        return equalsHash(localFile.getSha256(), sha256(remoteContent));
    }

    private WorkspaceEntity requireGitHubWorkspace(Long userId, String workspaceId) {
        WorkspaceEntity workspace = workspaceMapper.selectOne(new LambdaQueryWrapper<WorkspaceEntity>()
            .eq(WorkspaceEntity::getId, workspaceId)
            .eq(WorkspaceEntity::getUserId, userId));
        if (workspace == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工作区不存在");
        }
        if (!"GITHUB".equals(workspace.getSourceType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只有 GitHub 工作区支持远端变更比较");
        }
        if (workspace.getRepositoryRef() == null || workspace.getRepositoryRef().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GitHub 工作区缺少远端分支信息");
        }
        return workspace;
    }

    private GitHubApiClient.CommitResponse requireCommit(
        String accessToken,
        RepositoryCoordinates repository,
        String reference
    ) {
        GitHubApiClient.CommitResponse commit = apiClient.repositoryCommit(
            accessToken, repository.owner(), repository.repository(), reference
        );
        if (commit == null || commit.sha() == null || commit.sha().isBlank()
            || commit.commit() == null || commit.commit().tree() == null
            || commit.commit().tree().sha() == null || commit.commit().tree().sha().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub 未返回有效的远端提交");
        }
        return commit;
    }

    private RepositoryCoordinates parseRepository(String fullName) {
        String cleanName = fullName == null ? "" : fullName.trim();
        if (!REPOSITORY_NAME.matcher(cleanName).matches()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GitHub 工作区缺少有效的仓库信息");
        }
        String[] parts = cleanName.split("/", 2);
        return new RepositoryCoordinates(parts[0], parts[1]);
    }

    private void validateBranch(String branch) {
        if (branch == null || !BRANCH_NAME.matcher(branch).matches()
            || branch.contains("..") || branch.startsWith("/") || branch.endsWith("/")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GitHub 分支名称无效");
        }
    }

    private void validateRelativePath(String path) {
        if (path == null || path.isBlank() || path.length() > 500 || path.startsWith("/")
            || path.contains("\\") || java.util.Arrays.stream(path.split("/")).anyMatch(".."::equals)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "工作区文件路径无效");
        }
    }

    private boolean hasFileBaseline(WorkspaceFileEntity file) {
        return file.getSourceBlobSha() != null && !file.getSourceBlobSha().isBlank()
            && file.getSourceSha256() != null && !file.getSourceSha256().isBlank();
    }

    private boolean equalsHash(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }

    private record RepositoryCoordinates(String owner, String repository) {
    }

    private record ComparedFile(
        GitHubWorkspaceFileChangeView view,
        boolean localChanged,
        boolean remoteChanged
    ) {
    }
}
