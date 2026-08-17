package com.skillforge.studio.security;

import com.skillforge.studio.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 从 HttpSession 解析当前平台用户。需要用户身份的控制器统一调用此组件，避免遗漏登录校验。
 */
@Component
public class CurrentUserProvider {
    private final AuthService authService;

    public CurrentUserProvider(AuthService authService) {
        this.authService = authService;
    }

    public Long requireUserId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(SessionKeys.USER_ID) instanceof Long userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        // 每次受保护操作都确认账号仍然存在且可用，避免停用账号继续使用旧会话。
        authService.findById(userId);
        return userId;
    }
}
