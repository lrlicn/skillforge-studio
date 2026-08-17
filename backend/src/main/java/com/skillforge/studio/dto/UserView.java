package com.skillforge.studio.dto;

import com.skillforge.studio.entity.UserEntity;

/** 返回前端的安全用户视图，明确排除 passwordHash 和账号内部状态。 */
public record UserView(Long id, String username, String email) {
    public static UserView from(UserEntity user) {
        return new UserView(user.getId(), user.getUsername(), user.getEmail());
    }
}
