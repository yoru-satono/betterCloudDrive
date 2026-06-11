package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.FileVersionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FileVersionRepository extends JpaRepository<FileVersionEntity, Long> {

    List<FileVersionEntity> findByFileIdOrderByVersionNumberDesc(Long fileId);

    @Query("SELECT v FROM FileVersionEntity v WHERE v.fileId = :fileId ORDER BY v.versionNumber DESC")
    List<FileVersionEntity> findTopVersionsByFileId(@Param("fileId") Long fileId, Pageable pageable);

    void deleteByFileId(Long fileId);

    int countByFileId(Long fileId);
}
