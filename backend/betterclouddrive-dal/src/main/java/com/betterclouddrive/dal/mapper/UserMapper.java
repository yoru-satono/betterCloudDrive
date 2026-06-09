package com.betterclouddrive.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.betterclouddrive.dal.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    @Update("UPDATE users SET storage_used = storage_used + #{delta} WHERE id = #{userId}")
    int updateStorageUsed(@Param("userId") Long userId, @Param("delta") long delta);

    @Select("SELECT COALESCE(SUM(storage_used), 0) FROM users WHERE deleted_at IS NULL")
    Long selectTotalStorageUsed();
}
