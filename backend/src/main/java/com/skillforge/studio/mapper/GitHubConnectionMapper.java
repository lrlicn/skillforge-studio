package com.skillforge.studio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.studio.entity.GitHubConnectionEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/** GitHub 账号连接的数据访问接口，基础查询和写入由 MyBatis-Plus 提供。 */
public interface GitHubConnectionMapper extends BaseMapper<GitHubConnectionEntity> {
}
