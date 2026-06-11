package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.FavoriteEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FavoriteRepository extends JpaRepository<FavoriteEntity, Long> {

    boolean existsByUserIdAndFileId(Long userId, Long fileId);

    Optional<FavoriteEntity> findByUserIdAndFileId(Long userId, Long fileId);

    Page<FavoriteEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    void deleteByUserIdAndFileId(Long userId, Long fileId);
}
