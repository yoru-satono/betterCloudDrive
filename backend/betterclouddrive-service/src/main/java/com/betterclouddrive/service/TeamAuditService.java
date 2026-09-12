package com.betterclouddrive.service;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.TeamOperationLogEntity;

import java.time.LocalDateTime;

public interface TeamAuditService {

    void record(Long teamId, Long actorUserId, String action, String targetType, Long targetId, String details);

    void recordFailure(Long teamId, Long actorUserId, String action, String targetType, Long targetId, Integer statusCode, String details);

    PageResult<TeamOperationLogEntity> listLogs(
            Long requesterUserId,
            Long teamId,
            Long actorUserId,
            String action,
            String targetType,
            Integer result,
            LocalDateTime startAt,
            LocalDateTime endAt,
            int page,
            int size);

    PageResult<TeamOperationLogEntity> exportLogs(
            Long requesterUserId,
            Long teamId,
            Long actorUserId,
            String action,
            String targetType,
            Integer result,
            LocalDateTime startAt,
            LocalDateTime endAt,
            int limit);

    PageResult<TeamOperationLogEntity> listExportBatch(
            Long requesterUserId, Long teamId, Long actorUserId, String action, String targetType,
            Integer result, LocalDateTime startAt, LocalDateTime endAt, int page, int size);

    void validateFilter(Long actorUserId, Integer result, LocalDateTime startAt, LocalDateTime endAt);
}
