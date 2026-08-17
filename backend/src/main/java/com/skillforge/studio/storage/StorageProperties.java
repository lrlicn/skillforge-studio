package com.skillforge.studio.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 本地存储配置。业务代码只依赖该配置和 StorageService，不直接拼接固定磁盘路径。
 */
@ConfigurationProperties(prefix = "skillforge.storage")
public record StorageProperties(Path localRoot) {
}
