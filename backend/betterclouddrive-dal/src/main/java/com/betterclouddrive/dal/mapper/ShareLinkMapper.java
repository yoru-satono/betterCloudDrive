package com.betterclouddrive.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.betterclouddrive.dal.entity.ShareLinkEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ShareLinkMapper extends BaseMapper<ShareLinkEntity> {

    @Select("SELECT * FROM share_links WHERE share_code = #{shareCode} AND is_canceled = FALSE")
    ShareLinkEntity selectByShareCode(@Param("shareCode") String shareCode);

    @Update("UPDATE share_links SET visit_count = visit_count + #{count} WHERE share_code = #{shareCode}")
    int incrementVisitCount(@Param("shareCode") String shareCode, @Param("count") int count);
}
