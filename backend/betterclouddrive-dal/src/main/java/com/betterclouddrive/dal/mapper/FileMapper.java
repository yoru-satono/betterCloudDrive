package com.betterclouddrive.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.betterclouddrive.dal.entity.FileEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FileMapper extends BaseMapper<FileEntity> {

    @Select("SELECT * FROM files WHERE user_id = #{userId} AND parent_id = #{parentId} AND is_deleted = #{isDeleted} ORDER BY file_type ASC, file_name ASC")
    List<FileEntity> selectByUserAndParent(@Param("userId") Long userId, @Param("parentId") Long parentId, @Param("isDeleted") Boolean isDeleted);

    @Select("SELECT * FROM files WHERE is_deleted = TRUE AND deleted_at < #{cutoff} ORDER BY deleted_at ASC LIMIT #{limit}")
    List<FileEntity> selectExpiredDeletedFiles(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
