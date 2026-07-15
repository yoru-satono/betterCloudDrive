package com.betterclouddrive.service;

import java.util.Map;

public interface TeamNotificationDispatchService {

    void enqueueUserNotification(Long userId, Long teamId, String type, String title, String content, String link, boolean email);

    void enqueueUserNotification(Long userId, Long teamId, String type, Map<String, Object> messageParams, String link, boolean email);

    void enqueueTeamMembersNotification(Long teamId, Long excludeUserId, String type, String title, String content, String link, boolean email);

}
