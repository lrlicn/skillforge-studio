package com.skillforge.studio.service;

import com.skillforge.studio.dto.GitHubRepositoryView;
import com.skillforge.studio.dto.GitHubSkillImportRequest;
import com.skillforge.studio.dto.GitHubSkillView;
import com.skillforge.studio.dto.WorkspaceImportResult;
import com.skillforge.studio.storage.WorkspaceFileInput;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
/** 协调 GitHub Token、仓库树扫描、文件下载和现有工作区导入服务。 */
public class GitHubRepositoryService {
    private static final Pattern REPOSITORY_NAME = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final int MAX_FILES = 500;
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;

    private final GitHubConnectionService connectionService;
    private final GitHubApiClient apiClient;
    private final WorkspaceService workspaceService;

    public GitHubRepositoryService(
        GitHubConnectionService connectionService,
        GitHubApiClient apiClient,
        WorkspaceService workspaceService
    ) {
        this.connectionService = connectionService;
        this.apiClient = apiClient;
        this.workspaceService = workspaceService;
    }

    public List<GitHubRepositoryView> repositories(Long userId) {
        return apiClient.repositories(connectionService.requireAccessToken(userId));
    }

    /** 递归树中只把文件名为 SKILL.md 的目录识别为 skill，不根据普通 Markdown 文件猜测。 */
    public List<GitHubSkillView> skills(Long userId, String repositoryFullName, String reference) {
        RepositoryCoordinates repository = parseRepository(repositoryFullName);
        String cleanReference = requireReference(reference);
        GitHubApiClient.TreeResponse tree = requireCompleteTree(
            connectionService.requireAccessToken(userId), repository, cleanReference
        );
        return skillsFromTree(repository.repository(), tree);
    }

    /**
     * 导入前重新读取仓库树并校验用户提交的 skill 路径，避免前端伪造任意远端路径。
     * 下载完成后才开启数据库事务，网络抖动不会长期占用数据库连接。
     */
    public WorkspaceImportResult importSkills(Long userId, GitHubSkillImportRequest request) {
        RepositoryCoordinates repository = parseRepository(request.repositoryFullName());
        String reference = requireReference(request.branch());
        String accessToken = connectionService.requireAccessToken(userId);
        GitHubApiClient.CommitResponse commit = requireCommit(accessToken, repository, reference);
        GitHubApiClient.TreeResponse tree = requireCompleteTree(accessToken, repository, commit.commit().tree().sha());
        Set<String> availableSkills = skillsFromTree(repository.repository(), tree).stream()
            .map(GitHubSkillView::directoryPath)
            .collect(java.util.stream.Collectors.toSet());
        List<String> selectedSkills = request.skillPaths().stream().map(String::trim).distinct().toList();
        if (!availableSkills.containsAll(selectedSkills)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "选择的 skill 已不存在，请重新扫描仓库");
        }

        List<GitHubApiClient.TreeItemResponse> selectedFiles = tree.tree().stream()
            .filter(item -> "blob".equals(item.type()))
            .filter(item -> selectedSkills.stream().anyMatch(skillPath -> belongsToSkill(item.path(), skillPath)))
            .sorted(Comparator.comparing(GitHubApiClient.TreeItemResponse::path))
            .toList();
        validateImportSize(selectedFiles);

        List<WorkspaceFileInput> files = new ArrayList<>(selectedFiles.size());
        long downloadedBytes = 0L;
        for (GitHubApiClient.TreeItemResponse item : selectedFiles) {
            byte[] content = apiClient.blob(accessToken, repository.owner(), repository.repository(), item.sha());
            if (content.length > MAX_FILE_BYTES || downloadedBytes + content.length > MAX_TOTAL_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "所选 skills 的实际文件大小超过导入限制");
            }
            downloadedBytes += content.length;
            files.add(new WorkspaceFileInput(item.path(), content, null, item.sha()));
        }
        return workspaceService.importGitHub(
            userId,
            request.workspaceName(),
            request.repositoryFullName(),
            reference,
            commit.sha(),
            files
        );
    }

    /** 导入前冻结分支当前提交，后续树和文件都以同一个提交为基线。 */
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
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub 未返回有效的提交基线");
        }
        return commit;
    }

    private GitHubApiClient.TreeResponse requireCompleteTree(
        String accessToken,
        RepositoryCoordinates repository,
        String reference
    ) {
        GitHubApiClient.TreeResponse tree = apiClient.repositoryTree(
            accessToken, repository.owner(), repository.repository(), reference
        );
        if (tree == null || tree.tree() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub 未返回仓库文件树");
        }
        if (tree.truncated()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "仓库文件树过大，GitHub 返回结果已截断，暂不支持直接导入");
        }
        return tree;
    }

    private List<GitHubSkillView> skillsFromTree(String repositoryName, GitHubApiClient.TreeResponse tree) {
        return tree.tree().stream()
            .filter(item -> "blob".equals(item.type()) && item.path() != null)
            .filter(item -> item.path().toLowerCase(Locale.ROOT).endsWith("skill.md"))
            .filter(item -> item.path().equalsIgnoreCase("SKILL.md")
                || item.path().toLowerCase(Locale.ROOT).endsWith("/skill.md"))
            .map(item -> {
                int separator = item.path().lastIndexOf('/');
                String directory = separator < 0 ? "." : item.path().substring(0, separator);
                String name = separator < 0
                    ? repositoryName
                    : directory.substring(directory.lastIndexOf('/') + 1);
                return new GitHubSkillView(name, directory, item.path());
            })
            .sorted(Comparator.comparing(GitHubSkillView::directoryPath))
            .toList();
    }

    private boolean belongsToSkill(String filePath, String skillPath) {
        return ".".equals(skillPath) || filePath.equals(skillPath) || filePath.startsWith(skillPath + "/");
    }

    private void validateImportSize(List<GitHubApiClient.TreeItemResponse> files) {
        if (files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选 skills 中没有可导入文件");
        }
        if (files.size() > MAX_FILES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "所选 skills 超过 500 个文件");
        }
        long totalSize = 0L;
        Set<String> normalizedPaths = new HashSet<>();
        for (GitHubApiClient.TreeItemResponse file : files) {
            long size = file.size() == null ? 0L : file.size();
            if (size > MAX_FILE_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "GitHub 文件不能超过 20 MB: " + file.path());
            }
            totalSize += size;
            if (totalSize > MAX_TOTAL_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "所选 skills 总大小不能超过 100 MB");
            }
            if (!normalizedPaths.add(file.path().toLowerCase(Locale.ROOT))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "GitHub 仓库存在大小写冲突路径: " + file.path());
            }
        }
    }

    private RepositoryCoordinates parseRepository(String repositoryFullName) {
        String cleanName = repositoryFullName == null ? "" : repositoryFullName.trim();
        if (!REPOSITORY_NAME.matcher(cleanName).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitHub 仓库名称格式不合法");
        }
        String[] parts = cleanName.split("/", 2);
        return new RepositoryCoordinates(parts[0], parts[1]);
    }

    private String requireReference(String reference) {
        String cleanReference = reference == null ? "" : reference.trim();
        if (cleanReference.isEmpty() || cleanReference.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitHub 分支名称不合法");
        }
        return cleanReference;
    }

    private record RepositoryCoordinates(String owner, String repository) {
    }
}
