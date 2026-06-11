package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.FileEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FileRepository extends JpaRepository<FileEntity, Long>, JpaSpecificationExecutor<FileEntity> {

    List<FileEntity> findByUserIdAndParentIdAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(Long userId, Long parentId);

    List<FileEntity> findByUserIdAndParentIdIsNullAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(Long userId);

    @Query("SELECT f FROM FileEntity f WHERE f.isDeleted = true AND f.deletedAt < :cutoff ORDER BY f.id ASC")
    List<FileEntity> findExpiredDeletedFiles(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    Optional<FileEntity> findFirstByMd5HashAndIsDeletedFalseOrderByCreatedAtDesc(String md5Hash);

    List<FileEntity> findByUserIdAndIsDeletedTrue(Long userId);

    List<FileEntity> findByUserIdAndIsDeletedFalseAndFileNameContainingIgnoreCase(Long userId, String keyword);

    boolean existsByUserIdAndParentIdAndFileNameAndIsDeletedFalse(Long userId, Long parentId, String fileName);

    boolean existsByUserIdAndParentIdIsNullAndFileNameAndIsDeletedFalse(Long userId, String fileName);

    Optional<FileEntity> findByUserIdAndParentIdAndFileNameAndIsDeletedFalse(Long userId, Long parentId, String fileName);

    @Query("SELECT f FROM FileEntity f WHERE f.userId = :userId AND f.parentId IS NULL AND f.fileName = :fileName AND f.isDeleted = false")
    Optional<FileEntity> findRootByName(@Param("userId") Long userId, @Param("fileName") String fileName);
}
