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
@TableName("files")
public class FileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long parentId;

    private String fileName;

    @Builder.Default
    private String fileType = "file";

    private String mimeType;

    @Builder.Default
    private Long fileSize = 0L;

    private String storagePath;

    private String md5Hash;

    private String thumbnailPath;

    @Builder.Default
    private Boolean isDeleted = false;

    private LocalDateTime deletedAt;

    @Builder.Default
    private Integer versionCount = 1;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
