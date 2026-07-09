package com.betterclouddrive.service;

public interface TeamNotificationDispatchService {

    void enqueueUserNotification(Long userId, Long teamId, String type, String title, String content, String link, boolean email);

    void enqueueTeamMembersNotification(Long teamId, Long excludeUserId, String type, String title, String content, String link, boolean email);

    int processQueuedNotifications(int batchSize);
}
