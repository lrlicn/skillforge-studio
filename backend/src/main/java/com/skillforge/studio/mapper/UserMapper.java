package com.skillforge.studio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.studio.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/** 用户表数据访问接口，通用 CRUD 由 MyBatis-Plus 生成。 */
public interface UserMapper extends BaseMapper<UserEntity> {
}
