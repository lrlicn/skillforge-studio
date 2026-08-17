package com.skillforge.studio.storage;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 本机目录存储实现。所有文件必须位于配置的 upload 根目录内，任何越界路径都会被拒绝。
 */
@Service
public class LocalStorageService implements StorageService {
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg");
    private static final int MAX_RELATIVE_PATH_LENGTH = 500;
    private static final int MAX_FILENAME_LENGTH = 255;
    private final Path storageRoot;

    public LocalStorageService(StorageProperties properties) {
        if (properties.localRoot() == null) {
            throw new IllegalStateException("必须配置 skillforge.storage.local-root");
        }
        this.storageRoot = properties.localRoot().toAbsolutePath().normalize();
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建本地上传目录: " + storageRoot, exception);
        }
    }

    /**
     * 将一次导入的全部文件保存到独立工作区目录。保存前先校验全部路径，避免部分文件写入后才发现越界路径。
     */
    @Override
    public List<StoredFile> storeWorkspaceFiles(String workspaceId, MultipartFile[] files, List<String> relativePaths) {
        if (files == null || files.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一个文件");
        }
        if (relativePaths == null || files.length != relativePaths.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件数量与相对路径数量不一致");
        }

        Path workspaceRoot = storageRoot.resolve(workspaceId).normalize();
        ensureInsideStorageRoot(workspaceRoot);
        List<Path> safePaths = validateRelativePaths(relativePaths, workspaceRoot);

        try {
            Files.createDirectories(workspaceRoot);
            List<StoredFile> results = new ArrayList<>(files.length);
            for (int index = 0; index < files.length; index++) {
                results.add(storeSingleFile(files[index], safePaths.get(index), workspaceRoot));
            }
            return List.copyOf(results);
        } catch (IOException exception) {
            deleteWorkspace(workspaceId);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文件写入失败", exception);
        }
    }

    /**
     * GitHub 文件已由服务端完成大小和数量校验，这里仍重新执行路径约束、哈希计算和 MIME 探测。
     */
    @Override
    public List<StoredFile> storeWorkspaceFiles(String workspaceId, List<WorkspaceFileInput> files) {
        if (files == null || files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一个远端文件");
        }
        Path workspaceRoot = storageRoot.resolve(workspaceId).normalize();
        ensureInsideStorageRoot(workspaceRoot);
        List<Path> safePaths = validateRelativePaths(files.stream().map(WorkspaceFileInput::relativePath).toList(), workspaceRoot);

        try {
            Files.createDirectories(workspaceRoot);
            List<StoredFile> results = new ArrayList<>(files.size());
            for (int index = 0; index < files.size(); index++) {
                WorkspaceFileInput file = files.get(index);
                results.add(storeContent(file.content(), file.contentType(), safePaths.get(index), workspaceRoot));
            }
            return List.copyOf(results);
        } catch (IOException exception) {
            deleteWorkspace(workspaceId);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "远端文件写入失败", exception);
        }
    }

    /**
     * 按数据库中保存的相对 storagePath 读取资源，再次执行根目录约束，防止数据库脏数据造成任意文件读取。
     */
    @Override
    public Resource load(String storagePath) {
        try {
            Path target = storageRoot.resolve(storagePath).normalize();
            ensureInsideStorageRoot(target);
            if (!Files.isRegularFile(target)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在或已被移动");
            }
            return new FileSystemResource(target);
        } catch (InvalidPathException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件路径不合法", exception);
        }
    }

    /**
     * 在目标文件同目录写入临时文件后原子替换，避免进程中断留下半截文本。
     */
    @Override
    public TextFileReplacement replaceTextFile(String storagePath, byte[] content) {
        Path target = resolveExistingFile(storagePath);
        try {
            byte[] previousContent = Files.readAllBytes(target);
            writeAtomically(target, content);
            return new TextFileReplacement(content.length, sha256(content), previousContent);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文件保存失败", exception);
        }
    }

    /** 回滚恢复同样使用原子替换，防止补偿操作产生不完整文件。 */
    @Override
    public void restoreTextFile(String storagePath, byte[] previousContent) {
        Path target = resolveExistingFile(storagePath);
        try {
            writeAtomically(target, previousContent);
        } catch (IOException exception) {
            throw new IllegalStateException("无法恢复事务回滚前的文件内容: " + storagePath, exception);
        }
    }

    /**
     * 导入事务失败时清理本次创建的目录。删除范围被严格限制在 upload/workspaceId 下。
     */
    @Override
    public void deleteWorkspace(String workspaceId) {
        Path workspaceRoot = storageRoot.resolve(workspaceId).normalize();
        ensureInsideStorageRoot(workspaceRoot);
        if (!Files.exists(workspaceRoot)) {
            return;
        }
        try (var paths = Files.walk(workspaceRoot)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 清理失败不覆盖原始业务异常，残留文件可通过后续维护任务处理。
                }
            });
        } catch (IOException ignored) {
            // 无法遍历目录时同样保留原始业务异常。
        }
    }

    private List<Path> validateRelativePaths(List<String> relativePaths, Path workspaceRoot) {
        Set<String> uniquePaths = new HashSet<>();
        List<Path> safePaths = new ArrayList<>(relativePaths.size());
        for (String relativePath : relativePaths) {
            if (relativePath == null || relativePath.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件相对路径不能为空");
            }
            if (relativePath.length() > MAX_RELATIVE_PATH_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件相对路径不能超过 500 个字符");
            }
            try {
                String portablePath = relativePath.replace('\\', '/');
                Path normalized = Path.of(portablePath).normalize();
                if (normalized.isAbsolute() || normalized.getNameCount() == 0 || normalized.startsWith("..")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件路径不能超出工作区: " + relativePath);
                }
                String normalizedText = normalized.toString().replace('\\', '/');
                if (normalized.getFileName().toString().length() > MAX_FILENAME_LENGTH) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名不能超过 255 个字符");
                }
                if (!uniquePaths.add(normalizedText.toLowerCase(Locale.ROOT))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "存在重复文件路径: " + normalizedText);
                }
                Path target = workspaceRoot.resolve(normalized).normalize();
                if (!target.startsWith(workspaceRoot)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件路径不能超出工作区: " + relativePath);
                }
                safePaths.add(target);
            } catch (InvalidPathException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件路径不合法: " + relativePath, exception);
            }
        }
        return safePaths;
    }

    private StoredFile storeSingleFile(MultipartFile file, Path target, Path workspaceRoot) throws IOException {
        return storeStream(file.getInputStream(), file.getContentType(), target, workspaceRoot);
    }

    private StoredFile storeContent(byte[] content, String contentType, Path target, Path workspaceRoot) throws IOException {
        if (content == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "远端文件内容不能为空");
        }
        return storeStream(new ByteArrayInputStream(content), contentType, target, workspaceRoot);
    }

    /** 所有来源最终都通过同一写入路径生成哈希、MIME 和图片尺寸，避免元数据行为分叉。 */
    private StoredFile storeStream(InputStream source, String contentType, Path target, Path workspaceRoot) throws IOException {
        Files.createDirectories(target.getParent());
        MessageDigest digest = sha256Digest();
        try (InputStream input = new DigestInputStream(source, digest)) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }

        String relativePath = workspaceRoot.relativize(target).toString().replace('\\', '/');
        String storagePath = storageRoot.relativize(target).toString().replace('\\', '/');
        String mimeType = resolveMimeType(contentType, target);
        Integer[] dimensions = readImageDimensions(target, mimeType);
        String extension = extensionOf(target.getFileName().toString());
        String fileType = mimeType.startsWith("image/") || IMAGE_EXTENSIONS.contains(extension) ? "IMAGE" : "FILE";
        return new StoredFile(
            relativePath,
            storagePath,
            target.getFileName().toString(),
            fileType,
            mimeType,
            Files.size(target),
            HexFormat.of().formatHex(digest.digest()),
            dimensions[0],
            dimensions[1]
        );
    }

    private String resolveMimeType(String contentType, Path target) throws IOException {
        if (contentType != null && !contentType.isBlank()) {
            return contentType;
        }
        String detected = Files.probeContentType(target);
        return detected == null ? "application/octet-stream" : detected;
    }

    private Integer[] readImageDimensions(Path target, String mimeType) {
        if (!mimeType.startsWith("image/") || "image/svg+xml".equalsIgnoreCase(mimeType)) {
            return new Integer[]{null, null};
        }
        try {
            BufferedImage image = ImageIO.read(target.toFile());
            return image == null ? new Integer[]{null, null} : new Integer[]{image.getWidth(), image.getHeight()};
        } catch (IOException ignored) {
            return new Integer[]{null, null};
        }
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }

    private String sha256(byte[] content) {
        MessageDigest digest = sha256Digest();
        return HexFormat.of().formatHex(digest.digest(content));
    }

    private Path resolveExistingFile(String storagePath) {
        try {
            Path target = storageRoot.resolve(storagePath).normalize();
            ensureInsideStorageRoot(target);
            if (!Files.isRegularFile(target)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在或已被移动");
            }
            return target;
        } catch (InvalidPathException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件路径不合法", exception);
        }
    }

    private void writeAtomically(Path target, byte[] content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".skillforge-", ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String extensionOf(String filename) {
        int separator = filename.lastIndexOf('.');
        return separator < 0 ? "" : filename.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    private void ensureInsideStorageRoot(Path target) {
        if (!target.startsWith(storageRoot) || target.equals(storageRoot)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "存储路径超出允许范围");
        }
    }
}
