package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.FileTagEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FileTagRepository extends JpaRepository<FileTagEntity, Long> {

    boolean existsByFileIdAndTagId(Long fileId, Long tagId);

    Optional<FileTagEntity> findByFileIdAndTagId(Long fileId, Long tagId);

    Page<FileTagEntity> findByTagIdOrderByCreatedAtDesc(Long tagId, Pageable pageable);

    List<FileTagEntity> findByFileId(Long fileId);

    void deleteByTagId(Long tagId);

    void deleteByFileId(Long fileId);
}
