package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.TeamTaskEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TeamTaskRepository extends JpaRepository<TeamTaskEntity, Long>, JpaSpecificationExecutor<TeamTaskEntity> {
    Optional<TeamTaskEntity> findByIdAndTeamId(Long id, Long teamId);

    List<TeamTaskEntity> findByTaskTypeAndStatusOrderByCreatedAtAsc(String taskType, String status, Pageable pageable);

    List<TeamTaskEntity> findByResultExpiresAtLessThanEqualAndResultPathIsNotNull(LocalDateTime resultExpiresAt);

    long countByStatus(String status);

    long countByStatusAndRetryCountGreaterThan(String status, Integer retryCount);
}
