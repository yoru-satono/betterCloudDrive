package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.FileTagEntity;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.TagEntity;
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
                      AND ft.teamId IS NULL
                      AND ft.tagId = :tagId
                      AND f.userId = :userId
                      AND f.teamId IS NULL
                      AND f.isDeleted = false
                    ORDER BY ft.createdAt DESC, f.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(f) FROM FileTagEntity ft JOIN FileEntity f ON ft.fileId = f.id
                    WHERE ft.userId = :userId
                      AND ft.teamId IS NULL
                      AND ft.tagId = :tagId
                      AND f.userId = :userId
                      AND f.teamId IS NULL
                      AND f.isDeleted = false
                    """
    )
    Page<FileEntity> findTaggedFiles(
            @Param("userId") Long userId,
            @Param("tagId") Long tagId,
            Pageable pageable);

    @Query(
            value = """
                    SELECT f FROM FileTagEntity ft JOIN FileEntity f ON ft.fileId = f.id
                    WHERE ft.userId = :userId
                      AND ft.teamId IS NULL
                      AND ft.tagId = :tagId
                      AND f.userId = :userId
                      AND f.teamId IS NULL
                      AND f.isDeleted = false
                      AND LOWER(f.fileName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    ORDER BY ft.createdAt DESC, f.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(f) FROM FileTagEntity ft JOIN FileEntity f ON ft.fileId = f.id
                    WHERE ft.userId = :userId
                      AND ft.teamId IS NULL
                      AND ft.tagId = :tagId
                      AND f.userId = :userId
                      AND f.teamId IS NULL
                      AND f.isDeleted = false
                      AND LOWER(f.fileName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    """
    )
    Page<FileEntity> findTaggedFilesByKeyword(
            @Param("userId") Long userId,
            @Param("tagId") Long tagId,
            @Param("keyword") String keyword,
            Pageable pageable);

    @Query("""
            SELECT f FROM FileTagEntity ft JOIN FileEntity f ON ft.fileId = f.id
            WHERE ft.teamId = :teamId
              AND ft.tagId = :tagId
              AND f.teamId = :teamId
              AND f.isDeleted = false
            ORDER BY ft.createdAt DESC
            """)
    List<FileEntity> findTeamTaggedFiles(
            @Param("teamId") Long teamId,
            @Param("tagId") Long tagId);

    @Query("""
            SELECT f FROM FileTagEntity ft JOIN FileEntity f ON ft.fileId = f.id
            WHERE ft.teamId = :teamId
              AND ft.tagId = :tagId
              AND f.teamId = :teamId
              AND f.isDeleted = false
              AND LOWER(f.fileName) LIKE LOWER(CONCAT('%', :keyword, '%'))
            ORDER BY ft.createdAt DESC
            """)
    List<FileEntity> findTeamTaggedFilesByKeyword(
            @Param("teamId") Long teamId,
            @Param("tagId") Long tagId,
            @Param("keyword") String keyword);

    List<FileTagEntity> findByFileId(Long fileId);

    @Query("""
            SELECT ft.tagId, COUNT(ft.id) FROM FileTagEntity ft JOIN FileEntity f ON ft.fileId = f.id
            WHERE ft.userId = :userId
              AND ft.teamId IS NULL
              AND f.userId = :userId
              AND f.teamId IS NULL
              AND f.isDeleted = false
            GROUP BY ft.tagId
            """)
    List<Object[]> countActivePersonalFilesByTag(@Param("userId") Long userId);

    @Query("""
            SELECT t FROM FileTagEntity ft JOIN TagEntity t ON ft.tagId = t.id
            WHERE ft.fileId = :fileId
              AND ft.userId = :userId
              AND ft.teamId IS NULL
              AND t.userId = :userId
              AND t.teamId IS NULL
            ORDER BY t.tagName ASC
            """)
    List<TagEntity> findPersonalTagsForFile(@Param("userId") Long userId, @Param("fileId") Long fileId);

    void deleteByUserIdAndFileId(Long userId, Long fileId);

    void deleteByTagId(Long tagId);

    void deleteByFileId(Long fileId);
}
