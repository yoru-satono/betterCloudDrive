package com.betterclouddrive.service;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.OperationLogEntity;

import java.time.LocalDateTime;

public interface OperationLogService {
    void logAsync(Long userId, String actionType, String targetType, Long targetId, String detail, String ipAddress, String userAgent);

    void logRequestAudit(Long userId, String actionType, String targetType, Long targetId,
                         String detail, String ipAddress, String userAgent, Integer result,
                         Integer durationMs, String requestId, String traceId,
                         Integer statusCode, Integer errorCode);

    void logSuccess(Long userId, String actionType, String targetType, Long targetId, String detail);

    /** Query operation logs with optional filters (paginated) */
    PageResult<OperationLogEntity> listLogs(Long userId, String actionType,
            String requestId, String traceId, Integer statusCode, Integer result,
            LocalDateTime startDate, LocalDateTime endDate, int page, int size);
}
