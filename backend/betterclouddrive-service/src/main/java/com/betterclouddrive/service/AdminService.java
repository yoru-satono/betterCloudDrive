package com.betterclouddrive.service;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.TeamOperationLogEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.service.dto.AdminTeamRecoveryDetails;
import com.betterclouddrive.service.dto.TeamMemberView;

import java.time.LocalDateTime;
import java.util.List;

import java.util.Map;

public interface AdminService {
    /** List users with optional keyword and status filters */
    PageResult<UserEntity> listUsers(String keyword, Integer status, int page, int size);

    /** Enable or disable a user (1=active, 0=disabled) */
    void updateUserStatus(Long userId, Integer status);

    /** Change a user's storage quota (bytes) */
    void updateUserQuota(Long userId, Long storageQuota);

    PageResult<TeamEntity> listTeams(String keyword, String status, int page, int size);

    TeamEntity getTeam(Long teamId);

    AdminTeamRecoveryDetails getTeamRecoveryDetails(Long teamId);

    TeamEntity updateTeamQuota(Long adminUserId, Long teamId, Long storageQuota);

    TeamEntity archiveTeam(Long adminUserId, Long teamId);

    TeamEntity unarchiveTeam(Long adminUserId, Long teamId);

    TeamEntity restoreTeam(Long adminUserId, Long teamId);

    void purgeTeam(Long adminUserId, Long teamId);

    List<TeamMemberView> listTeamMembers(Long teamId);

    PageResult<FileEntity> listTeamFiles(
            Long teamId,
            Long parentId,
            Boolean deleted,
            String keyword,
            int page,
            int size,
            String sortBy,
            String order);

    PageResult<TeamOperationLogEntity> listTeamAuditLogs(
            Long teamId,
            Long actorUserId,
            String action,
            String targetType,
            Integer result,
            LocalDateTime startAt,
            LocalDateTime endAt,
            int page,
            int size);

    PageResult<TeamOperationLogEntity> exportTeamAuditLogs(
            Long teamId,
            Long actorUserId,
            String action,
            String targetType,
            Integer result,
            LocalDateTime startAt,
            LocalDateTime endAt,
            int limit);

    /** Get system-level statistics */
    Map<String, Object> getSystemStats();
}
