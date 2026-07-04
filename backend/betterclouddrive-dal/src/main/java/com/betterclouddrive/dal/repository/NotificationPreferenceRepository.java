package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.NotificationPreferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreferenceEntity, Long> {

    List<NotificationPreferenceEntity> findByUserId(Long userId);

    Optional<NotificationPreferenceEntity> findByUserIdAndNotificationType(Long userId, String notificationType);
}
