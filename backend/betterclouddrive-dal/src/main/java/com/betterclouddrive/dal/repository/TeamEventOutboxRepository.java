package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.TeamEventOutboxEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface TeamEventOutboxRepository extends JpaRepository<TeamEventOutboxEntity, Long>, JpaSpecificationExecutor<TeamEventOutboxEntity> {
    @Query("""
            SELECT e
            FROM TeamEventOutboxEntity e
            WHERE e.status = :status
              AND e.availableAt <= :availableAt
            ORDER BY CASE e.aggregateType
                       WHEN 'NOTIFICATION' THEN 0
                       WHEN 'TEAM_TASK' THEN 1
                       ELSE 2
                     END,
                     e.id ASC
            """)
    List<TeamEventOutboxEntity> findReadyByDispatchPriority(
            @Param("status") String status,
            @Param("availableAt") LocalDateTime availableAt,
            Pageable pageable);

    long countByStatus(String status);

    long countByStatusAndRetryCountGreaterThan(String status, Integer retryCount);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE TeamEventOutboxEntity e
            SET e.status = :pendingStatus,
                e.availableAt = :availableAt,
                e.lockedAt = null,
                e.errorMessage = :errorMessage,
                e.updatedAt = :updatedAt
            WHERE e.status = :processingStatus
              AND e.lockedAt <= :lockedBefore
            """)
    int resetStaleProcessing(
            @Param("processingStatus") String processingStatus,
            @Param("pendingStatus") String pendingStatus,
            @Param("lockedBefore") LocalDateTime lockedBefore,
            @Param("availableAt") LocalDateTime availableAt,
            @Param("updatedAt") LocalDateTime updatedAt,
            @Param("errorMessage") String errorMessage);
}
