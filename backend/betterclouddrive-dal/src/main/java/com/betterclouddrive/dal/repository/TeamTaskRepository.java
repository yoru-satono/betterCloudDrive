package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.TeamTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TeamTaskRepository extends JpaRepository<TeamTaskEntity, Long>, JpaSpecificationExecutor<TeamTaskEntity> {
    Optional<TeamTaskEntity> findByIdAndTeamId(Long id, Long teamId);

    @Query(value = """
            WITH candidates AS (
                SELECT id
                FROM team_tasks
                WHERE task_type = :taskType AND status = 'PENDING'
                ORDER BY created_at ASC, id ASC
                FOR UPDATE SKIP LOCKED
                LIMIT :batchSize
            )
            UPDATE team_tasks task
            SET status = 'RUNNING', progress = 0, started_at = CURRENT_TIMESTAMP,
                completed_at = NULL, updated_at = CURRENT_TIMESTAMP
            FROM candidates
            WHERE task.id = candidates.id
            RETURNING task.*
            """, nativeQuery = true)
    List<TeamTaskEntity> claimPendingTasks(@Param("taskType") String taskType, @Param("batchSize") int batchSize);

    @Modifying
    @Query("""
            UPDATE TeamTaskEntity task
            SET task.status = 'PENDING', task.progress = 0, task.startedAt = null,
                task.completedAt = null, task.updatedAt = :now,
                task.retryCount = task.retryCount + 1
            WHERE task.status = 'RUNNING' AND task.startedAt < :cutoff
            """)
    int recoverTimedOutTasks(@Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now);

    List<TeamTaskEntity> findByResultExpiresAtLessThanEqualAndResultPathIsNotNull(LocalDateTime resultExpiresAt);

    long countByStatus(String status);

    long countByStatusAndRetryCountGreaterThan(String status, Integer retryCount);
}
