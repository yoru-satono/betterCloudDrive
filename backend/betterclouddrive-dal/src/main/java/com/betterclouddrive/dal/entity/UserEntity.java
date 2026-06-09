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
@TableName("users")
public class UserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String passwordHash;

    private String email;

    @Builder.Default
    private Boolean emailVerified = false;

    private String nickname;

    private String avatarUrl;

    @Builder.Default
    private String role = "ROLE_USER";

    @Builder.Default
    private Integer status = 1;

    @Builder.Default
    private Long storageQuota = 10737418240L;

    @Builder.Default
    private Long storageUsed = 0L;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;
}
