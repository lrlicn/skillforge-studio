package com.skillforge.studio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.studio.entity.LocalAssetEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 本地图片元数据访问接口。
 */
@Mapper
public interface LocalAssetMapper extends BaseMapper<LocalAssetEntity> {
}
