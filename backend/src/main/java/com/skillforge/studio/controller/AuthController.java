package com.skillforge.studio.controller;

import com.skillforge.studio.dto.LoginRequest;
import com.skillforge.studio.dto.RegisterRequest;
import com.skillforge.studio.dto.UserView;
import com.skillforge.studio.entity.UserEntity;
import com.skillforge.studio.security.SessionKeys;
import com.skillforge.studio.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "账号认证")
/**
 * 平台账号认证接口。会话中仅保存用户主键，用户详情每次从数据库读取。
 */
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @Operation(summary = "注册账号")
    public UserView register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        UserEntity user = authService.register(request);
        establishSession(httpRequest, user.getId());
        return UserView.from(user);
    }

    @PostMapping("/login")
    @Operation(summary = "账号登录")
    public UserView login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        UserEntity user = authService.login(request);
        establishSession(httpRequest, user.getId());
        return UserView.from(user);
    }

    @PostMapping("/logout")
    @Operation(summary = "退出登录")
    public void logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    @GetMapping("/me")
    @Operation(summary = "获取当前用户")
    public UserView me(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute(SessionKeys.USER_ID) instanceof Long userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return UserView.from(authService.findById(userId));
    }

    /**
     * 认证成功后更新会话标识，阻断攻击者预先固定 Session ID；会话中只保存用户主键。
     */
    private void establishSession(HttpServletRequest request, Long userId) {
        var session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(SessionKeys.USER_ID, userId);
    }
}
