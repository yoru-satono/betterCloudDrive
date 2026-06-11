package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.UploadSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface UploadSessionRepository extends JpaRepository<UploadSessionEntity, String> {

    List<UploadSessionEntity> findByStatusAndCreatedAtLessThanEqual(Integer status, LocalDateTime cutoff);
}
