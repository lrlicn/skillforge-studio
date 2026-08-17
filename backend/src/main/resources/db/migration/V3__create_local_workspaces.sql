-- 本地工作区记录用户主动导入的数据源，不允许保存任意磁盘扫描路径。
CREATE TABLE workspaces (
    id CHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_label VARCHAR(255) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'IMPORTING',
    file_count INT NOT NULL DEFAULT 0,
    total_size BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_workspaces_user (user_id),
    CONSTRAINT fk_workspaces_user FOREIGN KEY (user_id) REFERENCES users(id)
);

-- 文件表保存工作区目录索引，storage_path 始终是 upload 根目录下的相对路径。
CREATE TABLE workspace_files (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    workspace_id CHAR(36) NOT NULL,
    relative_path VARCHAR(500) NOT NULL,
    storage_path VARCHAR(1000) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    file_type VARCHAR(32) NOT NULL,
    mime_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_workspace_file_path (workspace_id, relative_path),
    INDEX idx_workspace_files_workspace (workspace_id),
    CONSTRAINT fk_workspace_files_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE
);

-- 图片表补充文件关联和大小，便于后续替换本地存储实现。
ALTER TABLE local_assets
    ADD COLUMN file_id BIGINT NULL AFTER workspace_id,
    ADD COLUMN size_bytes BIGINT NOT NULL DEFAULT 0 AFTER mime_type,
    ADD UNIQUE INDEX uk_local_assets_file (file_id),
    ADD CONSTRAINT fk_local_assets_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_local_assets_file FOREIGN KEY (file_id) REFERENCES workspace_files(id) ON DELETE CASCADE;
