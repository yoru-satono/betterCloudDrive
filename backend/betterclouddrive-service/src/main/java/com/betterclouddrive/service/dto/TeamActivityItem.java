package com.betterclouddrive.service.dto;

import java.time.LocalDateTime;

public record TeamActivityItem(
        Long id,
        Long teamId,
        Long actorUserId,
        String action,
        String targetType,
        Long targetId,
        String summary,
        LocalDateTime createdAt) {
}
