package com.skillforge.studio.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.studio.dto.WorkspaceImportResult;
import com.skillforge.studio.dto.FileContentUpdateResult;
import com.skillforge.studio.dto.WorkspaceSummary;
import com.skillforge.studio.dto.WorkspaceTreeNode;
import com.skillforge.studio.entity.LocalAssetEntity;
import com.skillforge.studio.entity.WorkspaceEntity;
import com.skillforge.studio.entity.WorkspaceFileEntity;
import com.skillforge.studio.mapper.LocalAssetMapper;
import com.skillforge.studio.mapper.WorkspaceFileMapper;
import com.skillforge.studio.mapper.WorkspaceMapper;
import com.skillforge.studio.storage.StorageService;
import com.skillforge.studio.storage.StoredFile;
import com.skillforge.studio.storage.TextFileReplacement;
import com.skillforge.studio.storage.WorkspaceFileInput;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Set;
import java.nio.charset.StandardCharsets;

/**
 * 本地工作区应用服务，协调数据库事务与文件系统存储。
 */
@Service
public class WorkspaceService {
    private static final int MAX_TEXT_FILE_BYTES = 2 * 1024 * 1024;
    private static final Set<String> EDITABLE_TEXT_EXTENSIONS = Set.of(
        "md", "markdown", "txt", "html", "htm", "css", "js", "jsx", "ts", "tsx",
        "json", "xml", "yaml", "yml", "toml", "properties", "py", "java", "sql", "sh"
    );
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceFileMapper workspaceFileMapper;
    private final LocalAssetMapper localAssetMapper;
    private final StorageService storageService;

    public WorkspaceService(
        WorkspaceMapper workspaceMapper,
        WorkspaceFileMapper workspaceFileMapper,
        LocalAssetMapper localAssetMapper,
        StorageService storageService
    ) {
        this.workspaceMapper = workspaceMapper;
        this.workspaceFileMapper = workspaceFileMapper;
        this.localAssetMapper = localAssetMapper;
        this.storageService = storageService;
    }

    /**
     * 创建工作区、保存文件并建立索引。任一步骤失败都会回滚数据库并清理本次上传目录。
     */
    @Transactional
    public WorkspaceImportResult importLocal(
        Long userId,
        String workspaceName,
        MultipartFile[] files,
        List<String> relativePaths
    ) {
        String cleanName = workspaceName == null ? "" : workspaceName.trim();
        if (cleanName.isEmpty() || cleanName.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作区名称长度应为 1-120 个字符");
        }
        if (files == null || files.length > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单次最多导入 500 个文件");
        }

        String workspaceId = UUID.randomUUID().toString();
        registerRollbackCleanup(workspaceId);
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setName(cleanName);
        workspace.setSourceType("LOCAL");
        workspace.setSourceLabel("用户主动导入");
        workspace.setStatus("IMPORTING");
        workspace.setFileCount(0);
        workspace.setTotalSize(0L);
        workspaceMapper.insert(workspace);

        List<StoredFile> storedFiles = storageService.storeWorkspaceFiles(workspaceId, files, relativePaths);
        long totalSize = 0L;
        for (StoredFile storedFile : storedFiles) {
            WorkspaceFileEntity fileEntity = toFileEntity(workspaceId, storedFile);
            workspaceFileMapper.insert(fileEntity);
            if (storedFile.image()) {
                localAssetMapper.insert(toAssetEntity(workspaceId, fileEntity.getId(), storedFile));
            }
            totalSize += storedFile.sizeBytes();
        }

        workspace.setStatus("READY");
        workspace.setFileCount(storedFiles.size());
        workspace.setTotalSize(totalSize);
        workspaceMapper.updateById(workspace);
        return new WorkspaceImportResult(workspaceId, cleanName, "LOCAL", storedFiles.size(), totalSize);
    }

    /**
     * 将用户明确选择的 GitHub skills 创建为远端来源工作区。
     * 远端下载在进入本方法前完成，数据库事务只覆盖本地落盘与索引写入，避免长时间占用数据库连接。
     */
    @Transactional
    public WorkspaceImportResult importGitHub(
        Long userId,
        String workspaceName,
        String repositoryFullName,
        String repositoryRef,
        String repositoryBaseCommitSha,
        List<WorkspaceFileInput> files
    ) {
        String cleanName = workspaceName == null ? "" : workspaceName.trim();
        if (cleanName.isEmpty() || cleanName.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作区名称长度应为 1-120 个字符");
        }
        if (repositoryFullName == null || repositoryFullName.isBlank() || repositoryFullName.length() > 255
            || repositoryRef == null || repositoryRef.isBlank() || repositoryRef.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitHub 仓库或分支信息不合法");
        }
        if (repositoryBaseCommitSha == null || repositoryBaseCommitSha.isBlank()
            || repositoryBaseCommitSha.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitHub 导入基线不合法");
        }
        if (files == null || files.isEmpty() || files.size() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitHub 工作区文件数量应为 1-500 个");
        }

        String workspaceId = UUID.randomUUID().toString();
        registerRollbackCleanup(workspaceId);
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setName(cleanName);
        workspace.setSourceType("GITHUB");
        workspace.setSourceLabel(repositoryFullName + " @ " + repositoryRef);
        workspace.setRepositoryFullName(repositoryFullName);
        workspace.setRepositoryRef(repositoryRef);
        workspace.setRepositoryBaseCommitSha(repositoryBaseCommitSha);
        workspace.setStatus("IMPORTING");
        workspace.setFileCount(0);
        workspace.setTotalSize(0L);
        workspaceMapper.insert(workspace);

        List<StoredFile> storedFiles = storageService.storeWorkspaceFiles(workspaceId, files);
        long totalSize = 0L;
        for (int index = 0; index < storedFiles.size(); index++) {
            StoredFile storedFile = storedFiles.get(index);
            WorkspaceFileInput sourceFile = files.get(index);
            WorkspaceFileEntity fileEntity = toFileEntity(workspaceId, storedFile);
            fileEntity.setSourceBlobSha(sourceFile.sourceBlobSha());
            fileEntity.setSourceSha256(storedFile.sha256());
            workspaceFileMapper.insert(fileEntity);
            if (storedFile.image()) {
                localAssetMapper.insert(toAssetEntity(workspaceId, fileEntity.getId(), storedFile));
            }
            totalSize += storedFile.sizeBytes();
        }

        workspace.setStatus("READY");
        workspace.setFileCount(storedFiles.size());
        workspace.setTotalSize(totalSize);
        workspaceMapper.updateById(workspace);
        return new WorkspaceImportResult(workspaceId, cleanName, "GITHUB", storedFiles.size(), totalSize);
    }

    /**
     * 只返回当前用户拥有的工作区，避免通过猜测 UUID 查看他人导入记录。
     */
    public List<WorkspaceSummary> list(Long userId) {
        return workspaceMapper.selectList(new LambdaQueryWrapper<WorkspaceEntity>()
                .eq(WorkspaceEntity::getUserId, userId)
                .orderByDesc(WorkspaceEntity::getUpdatedAt))
            .stream()
            .map(WorkspaceSummary::from)
            .toList();
    }

    /**
     * 将数据库中的扁平相对路径转换为稳定排序的目录树，前端无需自行猜测层级。
     */
    public List<WorkspaceTreeNode> tree(Long userId, String workspaceId) {
        requireWorkspace(userId, workspaceId);
        List<WorkspaceFileEntity> files = workspaceFileMapper.selectList(new LambdaQueryWrapper<WorkspaceFileEntity>()
            .eq(WorkspaceFileEntity::getWorkspaceId, workspaceId)
            .orderByAsc(WorkspaceFileEntity::getRelativePath));
        MutableTreeNode root = new MutableTreeNode("", "", "DIRECTORY");
        for (WorkspaceFileEntity file : files) {
            appendFile(root, file);
        }
        return root.children.values().stream().map(MutableTreeNode::toView).toList();
    }

    /** 修改工作区显示名称，不改变 UUID、来源或磁盘目录。 */
    @Transactional
    public WorkspaceSummary rename(Long userId, String workspaceId, String name) {
        WorkspaceEntity workspace = requireWorkspace(userId, workspaceId);
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty() || cleanName.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作区名称长度应为 1-120 个字符");
        }
        workspace.setName(cleanName);
        workspaceMapper.updateById(workspace);
        return WorkspaceSummary.from(workspaceMapper.selectById(workspaceId));
    }

    /**
     * 删除数据库聚合后再清理磁盘目录。数据库外键负责级联删除文件索引和图片元数据。
     */
    @Transactional
    public void delete(Long userId, String workspaceId) {
        requireWorkspace(userId, workspaceId);
        workspaceMapper.deleteById(workspaceId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storageService.deleteWorkspace(workspaceId);
            }
        });
    }

    /**
     * 使用行锁和 SHA-256 做乐观并发校验，保存成功后同步更新文件与工作区大小。
     */
    @Transactional
    public FileContentUpdateResult updateTextFile(
        Long userId,
        String workspaceId,
        Long fileId,
        String content,
        String expectedSha256
    ) {
        WorkspaceEntity workspace = requireWorkspace(userId, workspaceId);
        WorkspaceFileEntity file = workspaceFileMapper.selectOne(new LambdaQueryWrapper<WorkspaceFileEntity>()
            .eq(WorkspaceFileEntity::getId, fileId)
            .eq(WorkspaceFileEntity::getWorkspaceId, workspaceId)
            .last("FOR UPDATE"));
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工作区文件不存在");
        }
        if (!editableTextFile(file)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "该文件类型不支持在线编辑");
        }
        if (!file.getSha256().equalsIgnoreCase(expectedSha256)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "文件已被其他操作修改，请重新加载后再编辑");
        }

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_FILE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "在线编辑的文本文件不能超过 2 MB");
        }

        long previousSize = file.getSizeBytes();
        TextFileReplacement replacement = storageService.replaceTextFile(file.getStoragePath(), bytes);
        registerFileRestoreOnRollback(file.getStoragePath(), replacement.previousContent());

        file.setSizeBytes(replacement.sizeBytes());
        file.setSha256(replacement.sha256());
        workspaceFileMapper.updateById(file);

        long totalSize = Math.max(0L, workspace.getTotalSize() - previousSize + replacement.sizeBytes());
        workspace.setTotalSize(totalSize);
        workspaceMapper.updateById(workspace);
        return new FileContentUpdateResult(fileId, replacement.sizeBytes(), replacement.sha256(), totalSize);
    }

    /**
     * 读取文件前同时校验工作区归属和文件归属，storagePath 不接受前端直接传入。
     */
    public WorkspaceFileResource loadFile(Long userId, String workspaceId, Long fileId) {
        requireWorkspace(userId, workspaceId);
        WorkspaceFileEntity file = workspaceFileMapper.selectOne(new LambdaQueryWrapper<WorkspaceFileEntity>()
            .eq(WorkspaceFileEntity::getId, fileId)
            .eq(WorkspaceFileEntity::getWorkspaceId, workspaceId));
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工作区文件不存在");
        }
        return new WorkspaceFileResource(file, storageService.load(file.getStoragePath()));
    }

    private WorkspaceEntity requireWorkspace(Long userId, String workspaceId) {
        WorkspaceEntity workspace = workspaceMapper.selectOne(new LambdaQueryWrapper<WorkspaceEntity>()
            .eq(WorkspaceEntity::getId, workspaceId)
            .eq(WorkspaceEntity::getUserId, userId));
        if (workspace == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工作区不存在");
        }
        return workspace;
    }

    private WorkspaceFileEntity toFileEntity(String workspaceId, StoredFile storedFile) {
        WorkspaceFileEntity entity = new WorkspaceFileEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setRelativePath(storedFile.relativePath());
        entity.setStoragePath(storedFile.storagePath());
        entity.setOriginalName(storedFile.originalName());
        entity.setFileType(storedFile.fileType());
        entity.setMimeType(storedFile.mimeType());
        entity.setSizeBytes(storedFile.sizeBytes());
        entity.setSha256(storedFile.sha256());
        return entity;
    }

    /**
     * 文件系统不受数据库事务管理，因此在任何回滚场景下显式删除本次工作区目录。
     * afterCompletion 还能覆盖发生在方法返回之后的事务提交失败，这是普通 try/catch 捕获不到的情况。
     */
    private void registerRollbackCleanup(String workspaceId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    storageService.deleteWorkspace(workspaceId);
                }
            }
        });
    }

    private LocalAssetEntity toAssetEntity(String workspaceId, Long fileId, StoredFile storedFile) {
        LocalAssetEntity entity = new LocalAssetEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setFileId(fileId);
        entity.setRelativePath(storedFile.relativePath());
        entity.setStoragePath(storedFile.storagePath());
        entity.setMimeType(storedFile.mimeType());
        entity.setSizeBytes(storedFile.sizeBytes());
        entity.setWidth(storedFile.width());
        entity.setHeight(storedFile.height());
        entity.setSha256(storedFile.sha256());
        return entity;
    }

    private void appendFile(MutableTreeNode root, WorkspaceFileEntity file) {
        String[] segments = file.getRelativePath().replace('\\', '/').split("/");
        MutableTreeNode current = root;
        StringBuilder currentPath = new StringBuilder();
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (currentPath.length() > 0) {
                currentPath.append('/');
            }
            currentPath.append(segment);
            boolean fileNode = index == segments.length - 1;
            String key = currentPath.toString();
            current = current.children.computeIfAbsent(segment, ignored -> new MutableTreeNode(segment, key, fileNode ? "FILE" : "DIRECTORY"));
            if (fileNode) {
                current.file = file;
            }
        }
    }

    /**
     * 构树过程中使用可变节点，输出 DTO 时再冻结为不可变列表。
     */
    private static final class MutableTreeNode {
        private final String name;
        private final String path;
        private final String nodeType;
        private final Map<String, MutableTreeNode> children = new TreeMap<>();
        private WorkspaceFileEntity file;

        private MutableTreeNode(String name, String path, String nodeType) {
            this.name = name;
            this.path = path;
            this.nodeType = nodeType;
        }

        private WorkspaceTreeNode toView() {
            List<WorkspaceTreeNode> childViews = children.values().stream().map(MutableTreeNode::toView).toList();
            return new WorkspaceTreeNode(
                file == null ? "dir:" + path : "file:" + file.getId(),
                file == null ? null : file.getId(),
                name,
                path,
                nodeType,
                file == null ? null : file.getMimeType(),
                file == null ? null : file.getSizeBytes(),
                file == null ? null : file.getSha256(),
                childViews
            );
        }
    }

    /**
     * 文件元数据与 Spring Resource 的组合返回值，仅在服务层与控制器之间传递。
     */
    public record WorkspaceFileResource(WorkspaceFileEntity file, Resource resource) {
    }

    private boolean editableTextFile(WorkspaceFileEntity file) {
        if (file.getMimeType() != null && file.getMimeType().startsWith("text/")) {
            return true;
        }
        String name = file.getOriginalName();
        int separator = name.lastIndexOf('.');
        String extension = separator < 0 ? "" : name.substring(separator + 1).toLowerCase();
        return EDITABLE_TEXT_EXTENSIONS.contains(extension);
    }

    /** 数据库保存失败时恢复原始字节，保证文件系统与数据库中的哈希和大小保持一致。 */
    private void registerFileRestoreOnRollback(String storagePath, byte[] previousContent) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    storageService.restoreTextFile(storagePath, previousContent);
                }
            }
        });
    }
}
