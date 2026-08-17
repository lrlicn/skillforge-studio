package com.skillforge.studio.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件存储端口。工作区服务通过该接口保存和读取文件，不感知底层是本机目录还是对象存储。
 */
public interface StorageService {
    List<StoredFile> storeWorkspaceFiles(String workspaceId, MultipartFile[] files, List<String> relativePaths);

    /** 保存服务端从可信远端主动获取的文件，同时复用与本地上传一致的路径安全规则。 */
    List<StoredFile> storeWorkspaceFiles(String workspaceId, List<WorkspaceFileInput> files);

    Resource load(String storagePath);

    /** 原子替换一个已存在的文本文件，并返回事务回滚所需的旧内容。 */
    TextFileReplacement replaceTextFile(String storagePath, byte[] content);

    /** 数据库事务回滚时恢复文件替换前的内容。 */
    void restoreTextFile(String storagePath, byte[] previousContent);

    void deleteWorkspace(String workspaceId);
}
