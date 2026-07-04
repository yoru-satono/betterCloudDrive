package com.betterclouddrive.service;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.NotificationEntity;

import java.util.List;
import java.util.Map;

public interface NotificationService {

    PageResult<NotificationEntity> listNotifications(Long userId, Boolean unreadOnly, String type, int page, int size);

    long countUnread(Long userId);

    List<String> supportedPreferenceTypes();

    Map<String, Boolean> getPreferences(Long userId);

    Map<String, Boolean> updatePreferences(Long userId, Map<String, Boolean> preferences);

    void markRead(Long userId, Long notificationId);

    void markAllRead(Long userId);

    void notifyUser(Long userId, Long teamId, String type, String title, String content, String link, boolean email);

    void notifyTeamMembers(Long teamId, Long actorUserId, String type, String title, String content, String link, boolean email);

    void checkTeamQuota(Long teamId);

    void notifyTeamQuotaExceeded(Long teamId);
}
