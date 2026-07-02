package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.UploadSessionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UploadSessionRepository extends JpaRepository<UploadSessionEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM UploadSessionEntity s WHERE s.id = :sessionId")
    Optional<UploadSessionEntity> findByIdForUpdate(@Param("sessionId") String sessionId);

    List<UploadSessionEntity> findByStatusAndCreatedAtLessThanEqual(Integer status, LocalDateTime cutoff);

    List<UploadSessionEntity> findByTeamId(Long teamId);
}
