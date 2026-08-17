-- GitHub 工作区记录导入时的 Commit，推送前可判断目标分支是否已经向前变化。
ALTER TABLE workspaces
    ADD COLUMN repository_base_commit_sha VARCHAR(64) NULL AFTER repository_ref;

-- 每个文件同时记录远端 Blob 和本地内容基线，用于区分本地修改、远端修改和双向冲突。
ALTER TABLE workspace_files
    ADD COLUMN source_blob_sha VARCHAR(64) NULL AFTER sha256,
    ADD COLUMN source_sha256 CHAR(64) NULL AFTER source_blob_sha;
