package com.betterclouddrive.web.dto.response;

import com.betterclouddrive.dal.entity.TeamFilePermissionEntity;

import java.time.LocalDateTime;

public record TeamFilePermissionResponse(
        Long id,
        Long teamId,
        Long fileId,
        String subjectType,
        Long subjectUserId,
        String subjectRole,
        Boolean canRead,
        Boolean canWrite,
        Boolean canShare,
        Boolean canDelete,
        Boolean canManage,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static TeamFilePermissionResponse from(TeamFilePermissionEntity entity) {
        return new TeamFilePermissionResponse(
                entity.getId(), entity.getTeamId(), entity.getFileId(), entity.getSubjectType(),
                entity.getSubjectUserId(), entity.getSubjectRole(), entity.getCanRead(), entity.getCanWrite(),
                entity.getCanShare(), entity.getCanDelete(), entity.getCanManage(), entity.getCreatedBy(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
