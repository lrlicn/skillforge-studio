package com.skillforge.studio.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册存储配置对象，后续切换 MinIO 或 S3 时无需修改工作区业务服务。
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {
}
