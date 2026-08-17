package com.skillforge.studio.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("github_connections")
/** GitHub 连接实体。访问令牌只允许保存 AES-GCM 密文，禁止在日志或接口响应中返回。 */
public class GitHubConnectionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long githubUserId;
    private String githubLogin;
    private String displayName;
    private String avatarUrl;
    private String accessTokenEncrypted;
    private String tokenScopes;
    private LocalDateTime tokenExpiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
