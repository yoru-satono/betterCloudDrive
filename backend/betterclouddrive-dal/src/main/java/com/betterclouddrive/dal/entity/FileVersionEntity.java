package com.betterclouddrive.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("file_versions")
public class FileVersionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long fileId;

    private Long userId;

    private Integer versionNumber;

    private Long fileSize;

    private String md5Hash;

    private String storagePath;

    private LocalDateTime createdAt;
}
