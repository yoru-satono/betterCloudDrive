package com.betterclouddrive.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.betterclouddrive.dal.entity.UserTokenEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserTokenMapper extends BaseMapper<UserTokenEntity> {

    @Select("SELECT * FROM user_tokens WHERE jti = #{jti}")
    UserTokenEntity selectByJti(@Param("jti") String jti);
}
