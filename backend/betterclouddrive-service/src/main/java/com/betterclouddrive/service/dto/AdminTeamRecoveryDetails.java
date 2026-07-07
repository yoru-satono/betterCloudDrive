package com.betterclouddrive.service.dto;

import com.betterclouddrive.dal.entity.TeamOperationLogEntity;

import java.time.LocalDateTime;
import java.util.List;

public record AdminTeamRecoveryDetails(
        Long teamId,
        String teamName,
        LocalDateTime deletedAt,
        Long deletedBy,
        String deletedByUsername,
        String deleteReason,
        LocalDateTime purgeAfter,
        LocalDateTime archivedAt,
        Long archivedBy,
        String archivedByUsername,
        long activeFileCount,
        long deletedFileCount,
        long memberCount,
        boolean restoreAvailable,
        boolean purgeAvailable,
        List<TeamOperationLogEntity> recentAuditLogs) {
}
