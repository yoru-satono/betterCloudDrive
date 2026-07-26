package com.betterclouddrive.service.dto;

import com.betterclouddrive.dal.entity.DeadLetterRecordEntity;

import java.time.LocalDateTime;
import java.util.UUID;

public record DeadLetterView(
        UUID id, String originalQueue, String taskType, String failureCategory,
        int deliveryCount, LocalDateTime firstFailedAt, LocalDateTime lastFailedAt,
        String requestId, String traceId, String status, LocalDateTime resolvedAt) {
    public static DeadLetterView from(DeadLetterRecordEntity entity) {
        return new DeadLetterView(entity.getId(), entity.getOriginalQueue(), entity.getTaskType(),
                entity.getFailureCategory(), entity.getDeliveryCount(), entity.getFirstFailedAt(),
                entity.getLastFailedAt(), entity.getRequestId(), entity.getTraceId(),
                entity.getStatus(), entity.getResolvedAt());
    }
}
