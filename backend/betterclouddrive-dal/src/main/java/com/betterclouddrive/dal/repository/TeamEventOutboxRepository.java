package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.TeamEventOutboxEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;
import java.util.List;

public interface TeamEventOutboxRepository extends JpaRepository<TeamEventOutboxEntity, Long>, JpaSpecificationExecutor<TeamEventOutboxEntity> {
    List<TeamEventOutboxEntity> findByStatusAndAvailableAtLessThanEqualOrderByIdAsc(String status, LocalDateTime availableAt, Pageable pageable);

    long countByStatus(String status);

    long countByStatusAndRetryCountGreaterThan(String status, Integer retryCount);
}
