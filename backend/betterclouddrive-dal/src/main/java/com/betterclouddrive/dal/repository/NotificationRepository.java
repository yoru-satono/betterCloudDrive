package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.NotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface NotificationRepository extends JpaRepository<NotificationEntity, Long>, JpaSpecificationExecutor<NotificationEntity> {

    long countByUserIdAndReadAtIsNull(Long userId);

    @Modifying
    @Query("UPDATE NotificationEntity n SET n.readAt = :readAt WHERE n.userId = :userId AND n.readAt IS NULL")
    int markAllReadByUserId(@Param("userId") Long userId, @Param("readAt") LocalDateTime readAt);
}
