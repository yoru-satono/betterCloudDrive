package com.betterclouddrive.service.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record TeamActivityItem(
        Long id,
        Long teamId,
        Long actorUserId,
        String action,
        String targetType,
        Long targetId,
        String summaryKey,
        Map<String, Object> summaryParams,
        LocalDateTime createdAt) {
}
