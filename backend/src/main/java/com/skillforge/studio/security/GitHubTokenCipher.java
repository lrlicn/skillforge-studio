package com.skillforge.studio.security;

import com.skillforge.studio.config.GitHubOAuthProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
/** 使用 AES-256-GCM 加密 GitHub Token，同时提供完整性校验，避免数据库密文被静默篡改。 */
public class GitHubTokenCipher {
    private static final String VERSION_PREFIX = "v1:";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final GitHubOAuthProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public GitHubTokenCipher(GitHubOAuthProperties properties) {
        this.properties = properties;
    }

    /** 启动 OAuth 安全链前主动校验密钥，避免授权完成后才发现令牌无法保存。 */
    public void validateConfiguration() {
        encryptionKey();
    }

    /** 每次加密生成独立随机 IV，并把版本、IV 和密文组合成可迁移的存储格式。 */
    public String encrypt(String plaintext) {
        if (!StringUtils.hasText(plaintext)) {
            throw new IllegalArgumentException("GitHub Token 不能为空");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("GitHub Token 加密失败", exception);
        }
    }

    /** 后续调用 GitHub API 时按相同格式解密；认证标签校验失败会直接拒绝返回明文。 */
    public String decrypt(String encryptedValue) {
        if (!StringUtils.hasText(encryptedValue) || !encryptedValue.startsWith(VERSION_PREFIX)) {
            throw new IllegalArgumentException("GitHub Token 密文格式无效");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(encryptedValue.substring(VERSION_PREFIX.length()));
            if (payload.length <= IV_BYTES) {
                throw new IllegalArgumentException("GitHub Token 密文长度无效");
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] ciphertext = new byte[payload.length - IV_BYTES];
            System.arraycopy(payload, 0, iv, 0, IV_BYTES);
            System.arraycopy(payload, IV_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("GitHub Token 解密或完整性校验失败", exception);
        }
    }

    /** 配置值使用 Base64，解码后必须恰好为 32 字节，满足 AES-256 要求。 */
    private SecretKey encryptionKey() {
        if (!StringUtils.hasText(properties.tokenEncryptionKey())) {
            throw new IllegalStateException("启用 GitHub OAuth 时必须配置 token-encryption-key");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(properties.tokenEncryptionKey().trim());
            if (decoded.length != KEY_BYTES) {
                throw new IllegalStateException("token-encryption-key 解码后必须为 32 字节");
            }
            return new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("token-encryption-key 必须是有效的 Base64 字符串", exception);
        }
    }
}
