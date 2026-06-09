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
@TableName("share_links")
public class ShareLinkEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long fileId;

    private String shareCode;

    private String passwordHash;

    private LocalDateTime expireAt;

    private Integer maxDownloads;

    @Builder.Default
    private Integer downloadCount = 0;

    @Builder.Default
    private Integer visitCount = 0;

    @Builder.Default
    private Boolean isCanceled = false;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
