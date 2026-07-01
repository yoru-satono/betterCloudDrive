package com.betterclouddrive.service;

import com.betterclouddrive.dal.entity.TeamFilePermissionEntity;
import com.betterclouddrive.service.dto.TeamEffectivePermission;

import java.util.List;

public interface TeamPermissionService {

    TeamEffectivePermission effectivePermissions(Long userId, Long teamId, Long fileId);

    List<TeamFilePermissionEntity> listPermissions(Long actorUserId, Long teamId, Long fileId);

    TeamFilePermissionEntity upsertPermission(
            Long actorUserId,
            Long teamId,
            Long fileId,
            String subjectType,
            Long subjectUserId,
            String subjectRole,
            boolean canRead,
            boolean canWrite,
            boolean canShare,
            boolean canDelete,
            boolean canManage);

    void deletePermission(Long actorUserId, Long teamId, Long fileId, Long permissionId);

    void requireRead(Long userId, Long teamId, Long fileId);

    void requireWrite(Long userId, Long teamId, Long fileId);

    void requireShare(Long userId, Long teamId, Long fileId);

    void requireDelete(Long userId, Long teamId, Long fileId);

    void requireManage(Long userId, Long teamId, Long fileId);

    void requireParentWrite(Long userId, Long teamId, Long parentId);
}
