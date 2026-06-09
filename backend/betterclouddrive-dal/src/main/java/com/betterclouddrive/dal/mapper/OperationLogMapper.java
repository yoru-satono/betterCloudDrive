package com.betterclouddrive.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.betterclouddrive.dal.entity.OperationLogEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLogEntity> {
}
