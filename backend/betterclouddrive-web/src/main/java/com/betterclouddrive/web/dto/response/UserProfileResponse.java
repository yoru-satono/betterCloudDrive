package com.betterclouddrive.web.dto.response;

import com.betterclouddrive.dal.entity.UserEntity;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class UserProfileResponse {
    private Long id;
    private String username;
    private String email;
    private Boolean emailVerified;
    private String nickname;
    private String avatarUrl;
    private String role;
    private Integer status;
    private Long storageQuota;
    private Long storageUsed;
    private Boolean webdavEnabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public static UserProfileResponse from(UserEntity user) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .emailVerified(user.getEmailVerified())
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .role(user.getRole())
                .status(user.getStatus())
                .storageQuota(user.getStorageQuota())
                .storageUsed(user.getStorageUsed())
                .webdavEnabled(Boolean.TRUE.equals(user.getWebdavEnabled()))
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .deletedAt(user.getDeletedAt())
                .build();
    }
}
