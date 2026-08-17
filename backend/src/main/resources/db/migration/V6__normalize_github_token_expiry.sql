-- GitHub 非过期 Token 不返回 expires_in，Spring 会把过期时间构造成签发时刻。
-- 这类记录实际没有明确过期时间，统一改为 NULL，避免平台在授权完成后立即判定失效。
UPDATE github_connections
SET token_expires_at = NULL
WHERE token_expires_at IS NOT NULL
  AND token_expires_at <= DATE_ADD(updated_at, INTERVAL 60 SECOND);
