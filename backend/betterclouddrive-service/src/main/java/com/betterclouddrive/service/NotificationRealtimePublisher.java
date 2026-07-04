package com.betterclouddrive.service;

public interface NotificationRealtimePublisher {

    void publishNotificationsChanged(Long userId, long unreadCount);
}
