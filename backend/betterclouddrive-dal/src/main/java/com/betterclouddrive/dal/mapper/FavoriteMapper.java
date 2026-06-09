package com.betterclouddrive.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.betterclouddrive.dal.entity.FavoriteEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FavoriteMapper extends BaseMapper<FavoriteEntity> {
}
