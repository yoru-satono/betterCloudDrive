package com.betterclouddrive.service;

import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.service.dto.TeamTaskDownload;

import java.time.LocalDateTime;

public interface TeamAuditExportTaskService {

    TeamTaskEntity queueExport(
            Long requesterUserId,
            Long teamId,
            Long actorUserId,
            String action,
            String targetType,
            Integer result,
            LocalDateTime startAt,
            LocalDateTime endAt);

    int processPendingExports(int batchSize);

    TeamTaskDownload downloadExportResult(Long requesterUserId, Long teamId, Long taskId);

    int cleanupExpiredResults();
}
