-- GitHub 工作区保存结构化远端定位信息，为后续比较、同步和推送提供稳定依据。
ALTER TABLE workspaces
    ADD COLUMN repository_full_name VARCHAR(255) NULL AFTER source_label,
    ADD COLUMN repository_ref VARCHAR(255) NULL AFTER repository_full_name;
