-- 每个平台账号最多绑定一个 GitHub 账号，同一 GitHub 账号也不能被多个平台账号重复绑定。
CREATE TABLE github_connections (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    github_user_id BIGINT NOT NULL,
    github_login VARCHAR(120) NOT NULL,
    display_name VARCHAR(255) NULL,
    avatar_url VARCHAR(1000) NULL,
    access_token_encrypted TEXT NOT NULL,
    token_scopes VARCHAR(1000) NOT NULL DEFAULT '',
    token_expires_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_github_connections_user (user_id),
    UNIQUE KEY uk_github_connections_github_user (github_user_id),
    CONSTRAINT fk_github_connections_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
