package com.skillforge.studio.security;

/**
 * 统一维护服务端会话字段，避免控制器之间使用不一致的字符串键。
 */
public final class SessionKeys {
    public static final String USER_ID = "USER_ID";

    private SessionKeys() {
    }
}
