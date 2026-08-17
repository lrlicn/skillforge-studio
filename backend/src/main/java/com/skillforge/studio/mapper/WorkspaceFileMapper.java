package com.skillforge.studio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.studio.entity.WorkspaceFileEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工作区文件索引数据访问接口。
 */
@Mapper
public interface WorkspaceFileMapper extends BaseMapper<WorkspaceFileEntity> {
}
