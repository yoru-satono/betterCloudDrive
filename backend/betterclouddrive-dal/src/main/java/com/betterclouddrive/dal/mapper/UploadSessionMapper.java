package com.betterclouddrive.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.betterclouddrive.dal.entity.UploadSessionEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UploadSessionMapper extends BaseMapper<UploadSessionEntity> {
}
