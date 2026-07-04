package com.betterclouddrive.service;

public interface TeamEventOutboxService {

    void recordTeamEvent(Long teamId, Long actorUserId, String eventType, String targetType, Long targetId, String payload);

    void recordNotificationEvent(Long notificationId, Long userId, Long teamId, String eventType, String payload);
}
