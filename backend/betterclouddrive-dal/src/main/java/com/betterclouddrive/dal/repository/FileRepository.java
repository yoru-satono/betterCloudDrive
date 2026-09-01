package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.FileEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FileRepository extends JpaRepository<FileEntity, Long>, JpaSpecificationExecutor<FileEntity> {

    @Query("""
            SELECT f FROM FileEntity f
            WHERE f.userId = :userId AND f.teamId IS NULL AND f.parentId = :parentId AND f.isDeleted = false
            ORDER BY f.fileType ASC, f.fileName ASC
            """)
    List<FileEntity> findByUserIdAndParentIdAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(
            @Param("userId") Long userId, @Param("parentId") Long parentId);

    @Query("""
            SELECT f FROM FileEntity f
            WHERE f.userId = :userId AND f.teamId IS NULL AND f.parentId IS NULL AND f.isDeleted = false
            ORDER BY f.fileType ASC, f.fileName ASC
            """)
    List<FileEntity> findByUserIdAndParentIdIsNullAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(
            @Param("userId") Long userId);

    List<FileEntity> findByTeamIdAndParentIdAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(Long teamId, Long parentId);

    List<FileEntity> findByTeamIdAndParentIdIsNullAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(Long teamId);

    List<FileEntity> findByTeamId(Long teamId);

    long countByTeamIdAndIsDeletedFalse(Long teamId);

    long countByTeamIdAndIsDeletedTrue(Long teamId);

    @Query("SELECT f FROM FileEntity f WHERE f.isDeleted = true AND f.deletedAt < :cutoff ORDER BY f.id ASC")
    List<FileEntity> findExpiredDeletedFiles(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    @Query("""
            SELECT f FROM FileEntity f
            WHERE f.isDeleted = true AND f.deletedAt < :cutoff
              AND NOT EXISTS (
                  SELECT parent.id FROM FileEntity parent
                  WHERE parent.id = f.parentId AND parent.isDeleted = true
              )
            ORDER BY f.id ASC
            """)
    List<FileEntity> findExpiredDeletedRoots(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    @Modifying
    @Query("DELETE FROM FileEntity f WHERE f.userId = :userId AND f.teamId IS NULL AND f.isDeleted = true")
    int deleteByUserIdAndIsDeletedTrue(@Param("userId") Long userId);

    Optional<FileEntity> findFirstByMd5HashAndIsDeletedFalseOrderByCreatedAtDesc(String md5Hash);

    Optional<FileEntity> findFirstByStoragePathAndIsDeletedFalseOrderByIdDesc(String storagePath);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM FileEntity f WHERE f.id = :fileId")
    Optional<FileEntity> findByIdForUpdate(@Param("fileId") Long fileId);

    @Query("SELECT f FROM FileEntity f WHERE f.userId = :userId AND f.teamId IS NULL AND f.isDeleted = true")
    List<FileEntity> findByUserIdAndIsDeletedTrue(@Param("userId") Long userId);

    @Query(value = """
            SELECT f.* FROM files f
            WHERE f.user_id = :userId AND f.team_id IS NULL AND f.is_deleted = TRUE
              AND (:keyword IS NULL OR lower(f.file_name) LIKE lower(concat('%', :keyword, '%')))
              AND NOT EXISTS (
                  SELECT 1 FROM files parent
                  WHERE parent.id = f.parent_id AND parent.is_deleted = TRUE
              )
            ORDER BY f.deleted_at DESC NULLS LAST, f.id DESC
            """, countQuery = """
            SELECT count(*) FROM files f
            WHERE f.user_id = :userId AND f.team_id IS NULL AND f.is_deleted = TRUE
              AND (:keyword IS NULL OR lower(f.file_name) LIKE lower(concat('%', :keyword, '%')))
              AND NOT EXISTS (
                  SELECT 1 FROM files parent
                  WHERE parent.id = f.parent_id AND parent.is_deleted = TRUE
              )
            """, nativeQuery = true)
    Page<FileEntity> findPersonalRecycleRoots(
            @Param("userId") Long userId,
            @Param("keyword") String keyword,
            Pageable pageable);

    @Query("""
            SELECT f FROM FileEntity f
            WHERE f.userId = :userId AND f.teamId IS NULL AND f.isDeleted = false
              AND lower(f.fileName) LIKE lower(concat('%', :keyword, '%'))
            """)
    List<FileEntity> findByUserIdAndIsDeletedFalseAndFileNameContainingIgnoreCase(
            @Param("userId") Long userId, @Param("keyword") String keyword);

    @Query("""
            SELECT count(f) > 0 FROM FileEntity f
            WHERE f.userId = :userId AND f.teamId IS NULL AND f.parentId = :parentId
              AND f.fileName = :fileName AND f.isDeleted = false
            """)
    boolean existsByUserIdAndParentIdAndFileNameAndIsDeletedFalse(
            @Param("userId") Long userId, @Param("parentId") Long parentId, @Param("fileName") String fileName);

    @Query("""
            SELECT count(f) > 0 FROM FileEntity f
            WHERE f.userId = :userId AND f.teamId IS NULL AND f.parentId IS NULL
              AND f.fileName = :fileName AND f.isDeleted = false
            """)
    boolean existsByUserIdAndParentIdIsNullAndFileNameAndIsDeletedFalse(
            @Param("userId") Long userId, @Param("fileName") String fileName);

    @Query("""
            SELECT f FROM FileEntity f
            WHERE f.userId = :userId AND f.teamId IS NULL AND f.parentId = :parentId
              AND f.fileName = :fileName AND f.isDeleted = false
            """)
    Optional<FileEntity> findByUserIdAndParentIdAndFileNameAndIsDeletedFalse(
            @Param("userId") Long userId, @Param("parentId") Long parentId, @Param("fileName") String fileName);

    boolean existsByTeamIdAndParentIdAndFileNameAndIsDeletedFalse(Long teamId, Long parentId, String fileName);

    boolean existsByTeamIdAndParentIdIsNullAndFileNameAndIsDeletedFalse(Long teamId, String fileName);

    @Query("""
            SELECT f FROM FileEntity f
            WHERE f.userId = :userId AND f.teamId IS NULL AND f.parentId IS NULL
              AND f.fileName = :fileName AND f.isDeleted = false
            """)
    Optional<FileEntity> findRootByName(@Param("userId") Long userId, @Param("fileName") String fileName);
}
