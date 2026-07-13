package com.betterclouddrive.service.queue;

import java.util.Map;

public record NotificationDispatchPayload(
        Long userId,
        Long teamId,
        Long excludeUserId,
        String type,
        String titleKey,
        String contentKey,
        Map<String, Object> messageParams,
        String link,
        boolean email) {
}
