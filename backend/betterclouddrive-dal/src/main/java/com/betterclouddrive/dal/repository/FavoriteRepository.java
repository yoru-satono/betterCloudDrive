package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.FavoriteEntity;
import com.betterclouddrive.dal.entity.FileEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface FavoriteRepository extends JpaRepository<FavoriteEntity, Long> {

    boolean existsByUserIdAndFileId(Long userId, Long fileId);

    Optional<FavoriteEntity> findByUserIdAndFileId(Long userId, Long fileId);

    Page<FavoriteEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @Query(
            value = """
                    SELECT f FROM FavoriteEntity fav JOIN FileEntity f ON fav.fileId = f.id
                    WHERE fav.userId = :userId
                      AND f.isDeleted = false
                      AND f.teamId IS NULL
                      AND f.userId = :userId
                    ORDER BY fav.createdAt DESC, f.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(f) FROM FavoriteEntity fav JOIN FileEntity f ON fav.fileId = f.id
                    WHERE fav.userId = :userId
                      AND f.isDeleted = false
                      AND f.teamId IS NULL
                      AND f.userId = :userId
                    """
    )
    Page<FileEntity> findFavoriteFiles(
            @Param("userId") Long userId,
            Pageable pageable);

    @Query(
            value = """
                    SELECT f FROM FavoriteEntity fav JOIN FileEntity f ON fav.fileId = f.id
                    WHERE fav.userId = :userId
                      AND f.isDeleted = false
                      AND f.teamId IS NULL
                      AND f.userId = :userId
                      AND LOWER(f.fileName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    ORDER BY fav.createdAt DESC, f.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(f) FROM FavoriteEntity fav JOIN FileEntity f ON fav.fileId = f.id
                    WHERE fav.userId = :userId
                      AND f.isDeleted = false
                      AND f.teamId IS NULL
                      AND f.userId = :userId
                      AND LOWER(f.fileName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    """
    )
    Page<FileEntity> findFavoriteFilesByKeyword(
            @Param("userId") Long userId,
            @Param("keyword") String keyword,
            Pageable pageable);

    @Query("""
            SELECT f FROM FavoriteEntity fav JOIN FileEntity f ON fav.fileId = f.id
            WHERE fav.userId = :userId
              AND f.teamId = :teamId
              AND f.isDeleted = false
            ORDER BY fav.createdAt DESC, f.id DESC
            """)
    List<FileEntity> findTeamFavoriteFiles(
            @Param("userId") Long userId,
            @Param("teamId") Long teamId);

    @Query("""
            SELECT f FROM FavoriteEntity fav JOIN FileEntity f ON fav.fileId = f.id
            WHERE fav.userId = :userId
              AND f.teamId = :teamId
              AND f.isDeleted = false
              AND LOWER(f.fileName) LIKE LOWER(CONCAT('%', :keyword, '%'))
            ORDER BY fav.createdAt DESC, f.id DESC
            """)
    List<FileEntity> findTeamFavoriteFilesByKeyword(
            @Param("userId") Long userId,
            @Param("teamId") Long teamId,
            @Param("keyword") String keyword);

    void deleteByUserIdAndFileId(Long userId, Long fileId);
}
