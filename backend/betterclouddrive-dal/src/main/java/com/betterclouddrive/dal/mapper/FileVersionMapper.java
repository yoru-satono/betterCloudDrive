package com.betterclouddrive.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.betterclouddrive.dal.entity.FileVersionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FileVersionMapper extends BaseMapper<FileVersionEntity> {

    @Select("SELECT * FROM file_versions WHERE file_id = #{fileId} ORDER BY version_number DESC LIMIT #{limit}")
    List<FileVersionEntity> selectByFileId(@Param("fileId") Long fileId, @Param("limit") int limit);
}
