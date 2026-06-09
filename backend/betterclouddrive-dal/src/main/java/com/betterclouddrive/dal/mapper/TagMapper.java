package com.betterclouddrive.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.betterclouddrive.dal.entity.TagEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TagMapper extends BaseMapper<TagEntity> {
}
