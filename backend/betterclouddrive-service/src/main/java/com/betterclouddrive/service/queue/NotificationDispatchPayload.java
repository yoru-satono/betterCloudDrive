package com.betterclouddrive.service.queue;

public record NotificationDispatchPayload(
        Long userId,
        Long teamId,
        Long excludeUserId,
        String type,
        String title,
        String content,
        String link,
        boolean email) {
}
