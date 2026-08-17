package com.skillforge.studio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.studio.entity.WorkspaceEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工作区基础数据访问接口，通用 CRUD 由 MyBatis-Plus 提供。
 */
@Mapper
public interface WorkspaceMapper extends BaseMapper<WorkspaceEntity> {
}
