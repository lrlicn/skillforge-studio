package com.skillforge.studio.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.studio.dto.GitHubRepositoryView;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import org.springframework.web.util.UriBuilder;

@Component
/** GitHub REST API 客户端集中设置版本、认证头和错误映射，业务服务不直接拼接 HTTP 请求。 */
public class GitHubApiClient {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_REPOSITORY_PAGES = 5;
    private final RestClient restClient;

    public GitHubApiClient(RestClient.Builder builder) {
        this.restClient = builder
            .baseUrl("https://api.github.com")
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
            .defaultHeader(HttpHeaders.USER_AGENT, "SkillForge-Studio")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .build();
    }

    /** 分页读取最多 500 个当前 Token 可访问的仓库，避免单次响应无界增长。 */
    public List<GitHubRepositoryView> repositories(String accessToken) {
        List<GitHubRepositoryView> repositories = new ArrayList<>();
        for (int page = 1; page <= MAX_REPOSITORY_PAGES; page++) {
            int currentPage = page;
            List<RepositoryResponse> response = get(
                accessToken,
                uriBuilder -> uriBuilder.path("/user/repos")
                    .queryParam("visibility", "all")
                    .queryParam("affiliation", "owner,collaborator,organization_member")
                    .queryParam("sort", "updated")
                    .queryParam("direction", "desc")
                    .queryParam("per_page", PAGE_SIZE)
                    .queryParam("page", currentPage)
                    .build(),
                new ParameterizedTypeReference<>() {}
            );
            if (response == null || response.isEmpty()) break;
            repositories.addAll(response.stream().map(RepositoryResponse::toView).toList());
            if (response.size() < PAGE_SIZE) break;
        }
        return List.copyOf(repositories);
    }

    public TreeResponse repositoryTree(String accessToken, String owner, String repository, String reference) {
        return get(
            accessToken,
            uriBuilder -> uriBuilder.pathSegment("repos", owner, repository, "git", "trees", reference)
                .queryParam("recursive", "1")
                .build(),
            TreeResponse.class
        );
    }

    /**
     * 读取分支或标签当前指向的提交，同时取得提交对应的根 Tree SHA。
     * 后续文件树必须使用这个 Tree SHA，避免导入期间分支移动造成 Commit 与文件内容不一致。
     */
    public CommitResponse repositoryCommit(String accessToken, String owner, String repository, String reference) {
        return get(
            accessToken,
            uriBuilder -> uriBuilder.pathSegment("repos", owner, repository, "commits", reference).build(),
            CommitResponse.class
        );
    }

    /** Git Blob API 返回 Base64；使用 MIME 解码器兼容响应内容中的换行。 */
    public byte[] blob(String accessToken, String owner, String repository, String sha) {
        BlobResponse response = get(
            accessToken,
            uriBuilder -> uriBuilder.pathSegment("repos", owner, repository, "git", "blobs", sha).build(),
            BlobResponse.class
        );
        if (response == null || !"base64".equalsIgnoreCase(response.encoding()) || response.content() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub 返回了不支持的文件编码");
        }
        try {
            return Base64.getMimeDecoder().decode(response.content());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub 文件内容解码失败", exception);
        }
    }

    /** 创建 Blob，返回 GitHub 生成的 Blob SHA。 */
    public String createBlob(String accessToken, String owner, String repository, String content) {
        BlobCreateResponse response = post(
            accessToken,
            uriBuilder -> uriBuilder.pathSegment("repos", owner, repository, "git", "blobs").build(),
            new BlobCreateRequest(content, "base64"),
            BlobCreateResponse.class
        );
        return requireSha(response, "GitHub 未返回有效的 Blob");
    }

    /** 创建基于当前远端树的增量 Tree。 */
    public String createTree(
        String accessToken,
        String owner,
        String repository,
        String baseTreeSha,
        List<TreeCreateEntry> entries
    ) {
        TreeCreateResponse response = post(
            accessToken,
            uriBuilder -> uriBuilder.pathSegment("repos", owner, repository, "git", "trees").build(),
            new TreeCreateRequest(baseTreeSha, entries),
            TreeCreateResponse.class
        );
        return requireSha(response, "GitHub 未返回有效的 Tree");
    }

    /** 创建提交对象并返回提交 SHA。 */
    public CommitCreateResponse createCommit(
        String accessToken,
        String owner,
        String repository,
        String message,
        String treeSha,
        String parentSha
    ) {
        return post(
            accessToken,
            uriBuilder -> uriBuilder.pathSegment("repos", owner, repository, "git", "commits").build(),
            new CommitCreateRequest(message, new TreePointer(treeSha), List.of(parentSha)),
            CommitCreateResponse.class
        );
    }

    /** 非强制更新分支引用，远端已前进时 GitHub 会拒绝本次写入。 */
    public void updateBranchRef(
        String accessToken,
        String owner,
        String repository,
        String branch,
        String commitSha
    ) {
        patch(
            accessToken,
            uriBuilder -> uriBuilder.pathSegment("repos", owner, repository, "git", "refs", "heads", branch).build(),
            new RefUpdateRequest(commitSha, false),
            Object.class
        );
    }

    private <T> T get(String accessToken, Function<UriBuilder, URI> uriFunction, Class<T> responseType) {
        try {
            return restClient.get()
                .uri(uriFunction)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(responseType);
        } catch (RestClientException exception) {
            throw mapException(exception);
        }
    }

    private <T> T get(String accessToken, Function<UriBuilder, URI> uriFunction, ParameterizedTypeReference<T> responseType) {
        try {
            return restClient.get()
                .uri(uriFunction)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(responseType);
        } catch (RestClientException exception) {
            throw mapException(exception);
        }
    }

    private <T> T post(String accessToken, Function<UriBuilder, URI> uriFunction, Object body, Class<T> responseType) {
        try {
            return restClient.post()
                .uri(uriFunction)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .body(body)
                .retrieve()
                .body(responseType);
        } catch (RestClientException exception) {
            throw mapException(exception);
        }
    }

    private <T> T patch(String accessToken, Function<UriBuilder, URI> uriFunction, Object body, Class<T> responseType) {
        try {
            return restClient.patch()
                .uri(uriFunction)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .body(body)
                .retrieve()
                .body(responseType);
        } catch (RestClientException exception) {
            throw mapException(exception);
        }
    }

    private String requireSha(Object response, String message) {
        if (response == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
        String sha = response instanceof BlobCreateResponse blob ? blob.sha()
            : response instanceof TreeCreateResponse tree ? tree.sha()
            : null;
        if (sha == null || sha.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
        return sha;
    }

    /** 把 GitHub 和网络错误收敛为稳定业务文案，绝不把带认证头的请求对象写入异常响应。 */
    private ResponseStatusException mapException(RestClientException exception) {
        if (exception instanceof RestClientResponseException responseException) {
            return switch (responseException.getStatusCode().value()) {
                case 401 -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "GitHub 授权无效，请重新授权");
                case 403 -> new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "GitHub API 权限不足或请求频率已受限");
                case 404 -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GitHub 仓库、分支或文件不存在");
                default -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub API 请求失败");
            };
        }
        if (exception instanceof ResourceAccessException) {
            return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法安全连接 GitHub API，请检查网络代理和证书");
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub API 请求失败");
    }

    /** GitHub 仓库响应仅映射选择页需要的字段。 */
    public record RepositoryResponse(
        long id,
        String name,
        @JsonProperty("full_name") String fullName,
        OwnerResponse owner,
        @JsonProperty("private") boolean privateRepository,
        @JsonProperty("default_branch") String defaultBranch,
        String description,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("updated_at") OffsetDateTime updatedAt
    ) {
        GitHubRepositoryView toView() {
            return new GitHubRepositoryView(
                id, name, fullName, owner == null ? "" : owner.login(), privateRepository,
                defaultBranch, description, htmlUrl, updatedAt
            );
        }
    }

    public record OwnerResponse(String login) {
    }

    public record TreeResponse(String sha, boolean truncated, List<TreeItemResponse> tree) {
    }

    public record TreeItemResponse(String path, String mode, String type, String sha, Long size) {
    }

    public record CommitResponse(String sha, CommitDetails commit) {
    }

    public record CommitDetails(TreePointer tree) {
    }

    public record TreePointer(String sha) {
    }

    public record BlobResponse(String content, String encoding, long size) {
    }

    public record BlobCreateRequest(String content, String encoding) {
    }

    public record BlobCreateResponse(String sha) {
    }

    public record TreeCreateRequest(
        @JsonProperty("base_tree") String baseTree,
        List<TreeCreateEntry> tree
    ) {
    }

    public record TreeCreateEntry(
        String path,
        String mode,
        String type,
        String sha
    ) {
    }

    public record TreeCreateResponse(String sha) {
    }

    public record CommitCreateRequest(
        String message,
        TreePointer tree,
        List<String> parents
    ) {
    }

    public record CommitCreateResponse(
        String sha,
        @JsonProperty("html_url") String htmlUrl
    ) {
    }

    public record RefUpdateRequest(String sha, boolean force) {
    }
}
