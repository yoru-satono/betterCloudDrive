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
@TableName("upload_sessions")
public class UploadSessionEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private Long userId;

    private Long parentId;

    private String fileName;

    private Long fileSize;

    private String md5Hash;

    @Builder.Default
    private Integer chunkSize = 5242880;

    private Integer totalChunks;

    @Builder.Default
    private Integer receivedChunks = 0;

    private String storagePath;

    @Builder.Default
    private Integer status = 1;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
