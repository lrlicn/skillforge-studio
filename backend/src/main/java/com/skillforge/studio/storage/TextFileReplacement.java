package com.skillforge.studio.storage;

/**
 * 文本原子替换结果。previousContent 仅用于数据库事务回滚时恢复原文件，不向控制器暴露。
 */
public record TextFileReplacement(long sizeBytes, String sha256, byte[] previousContent) {
    public TextFileReplacement {
        previousContent = previousContent.clone();
    }

    @Override
    public byte[] previousContent() {
        return previousContent.clone();
    }
}
