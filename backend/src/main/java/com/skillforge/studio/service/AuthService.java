package com.skillforge.studio.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.studio.dto.LoginRequest;
import com.skillforge.studio.dto.RegisterRequest;
import com.skillforge.studio.entity.UserEntity;
import com.skillforge.studio.mapper.UserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
/**
 * 账号认证业务服务，负责唯一性检查、密码哈希验证和账号状态校验。
 */
public class AuthService {
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    /** 注册操作在一个事务中完成，用户名或邮箱重复时返回 409。 */
    public UserEntity register(RegisterRequest request) {
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase();
        Long duplicateCount = userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
            .eq(UserEntity::getUsername, username)
            .or()
            .eq(UserEntity::getEmail, email));
        if (duplicateCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名或邮箱已被使用");
        }

        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        return user;
    }

    /** 登录失败统一返回相同文案，避免泄露账号是否存在。 */
    public UserEntity login(LoginRequest request) {
        String account = request.account().trim();
        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
            .and(wrapper -> wrapper.eq(UserEntity::getUsername, account).or().eq(UserEntity::getEmail, account.toLowerCase()))
            .last("LIMIT 1"));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号当前不可用");
        }
        return user;
    }

    /** 会话中的用户已被删除或停用时按登录失效处理，旧会话不能绕过账号状态。 */
    public UserEntity findById(Long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录状态已失效");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号当前不可用");
        }
        return user;
    }
}
