package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.FolderZipDownloadCacheEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FolderZipDownloadCacheRepository extends JpaRepository<FolderZipDownloadCacheEntity, Long> {

    Optional<FolderZipDownloadCacheEntity> findByUserIdAndFolderId(Long userId, Long folderId);

    List<FolderZipDownloadCacheEntity> findByLastDownloadedAtLessThanEqual(LocalDateTime cutoff);
}
