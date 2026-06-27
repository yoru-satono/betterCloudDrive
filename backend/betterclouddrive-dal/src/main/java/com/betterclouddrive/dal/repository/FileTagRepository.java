package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.FileTagEntity;
import com.betterclouddrive.dal.entity.FileEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FileTagRepository extends JpaRepository<FileTagEntity, Long> {

    boolean existsByFileIdAndTagId(Long fileId, Long tagId);

    Optional<FileTagEntity> findByFileIdAndTagId(Long fileId, Long tagId);

    Page<FileTagEntity> findByTagIdOrderByCreatedAtDesc(Long tagId, Pageable pageable);

    @Query(
            value = """
                    SELECT f FROM FileTagEntity ft JOIN FileEntity f ON ft.fileId = f.id
                    WHERE ft.userId = :userId
                      AND ft.tagId = :tagId
                      AND f.userId = :userId
                      AND f.isDeleted = false
                      AND (:keyword IS NULL OR LOWER(f.fileName) LIKE LOWER(CONCAT('%', :keyword, '%')))
                    ORDER BY ft.createdAt DESC
                    """,
            countQuery = """
                    SELECT COUNT(f) FROM FileTagEntity ft JOIN FileEntity f ON ft.fileId = f.id
                    WHERE ft.userId = :userId
                      AND ft.tagId = :tagId
                      AND f.userId = :userId
                      AND f.isDeleted = false
                      AND (:keyword IS NULL OR LOWER(f.fileName) LIKE LOWER(CONCAT('%', :keyword, '%')))
                    """
    )
    Page<FileEntity> findTaggedFiles(
            @Param("userId") Long userId,
            @Param("tagId") Long tagId,
            @Param("keyword") String keyword,
            Pageable pageable);

    List<FileTagEntity> findByFileId(Long fileId);

    void deleteByTagId(Long tagId);

    void deleteByFileId(Long fileId);
}
