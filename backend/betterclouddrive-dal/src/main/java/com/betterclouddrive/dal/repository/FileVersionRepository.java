package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.FileVersionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FileVersionRepository extends JpaRepository<FileVersionEntity, Long> {

    List<FileVersionEntity> findByFileIdOrderByVersionNumberDesc(Long fileId);

    @Query("SELECT v FROM FileVersionEntity v WHERE v.fileId = :fileId ORDER BY v.versionNumber DESC")
    List<FileVersionEntity> findTopVersionsByFileId(@Param("fileId") Long fileId, Pageable pageable);

    @Query("SELECT COALESCE(MAX(v.versionNumber), 0) FROM FileVersionEntity v WHERE v.fileId = :fileId")
    int findMaxVersionNumberByFileId(@Param("fileId") Long fileId);

    Optional<FileVersionEntity> findByFileIdAndVersionNumber(Long fileId, Integer versionNumber);

    List<FileVersionEntity> findByFileIdIn(List<Long> fileIds);

    void deleteByFileId(Long fileId);

    int countByFileId(Long fileId);
}
