package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.OnlyOfficeCallbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

public interface OnlyOfficeCallbackRepository extends JpaRepository<OnlyOfficeCallbackEntity, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO onlyoffice_callbacks (
                team_id, file_id, actor_user_id, callback_key, status, content_url_hash, download_url, state, created_at, updated_at
            ) VALUES (
                :teamId, :fileId, :actorUserId, :callbackKey, :status, :contentUrlHash, :downloadUrl, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            ON CONFLICT (team_id, file_id, callback_key, status, content_url_hash) DO NOTHING
            """, nativeQuery = true)
    int insertProcessing(@Param("teamId") Long teamId,
                         @Param("fileId") Long fileId,
                         @Param("actorUserId") Long actorUserId,
                         @Param("callbackKey") String callbackKey,
                         @Param("status") Integer status,
                         @Param("contentUrlHash") String contentUrlHash,
                         @Param("downloadUrl") String downloadUrl);

    @Modifying
    @Query("""
            UPDATE OnlyOfficeCallbackEntity c
            SET c.taskId = :taskId,
                c.updatedAt = :updatedAt
            WHERE c.teamId = :teamId
              AND c.fileId = :fileId
              AND c.callbackKey = :callbackKey
              AND c.status = :status
              AND c.contentUrlHash = :contentUrlHash
            """)
    int attachTask(@Param("teamId") Long teamId,
                   @Param("fileId") Long fileId,
                   @Param("callbackKey") String callbackKey,
                   @Param("status") Integer status,
                   @Param("contentUrlHash") String contentUrlHash,
                   @Param("taskId") Long taskId,
                   @Param("updatedAt") LocalDateTime updatedAt);

    List<OnlyOfficeCallbackEntity> findByStateOrderByCreatedAtAsc(Integer state, Pageable pageable);

    @Modifying
    @Query("""
            UPDATE OnlyOfficeCallbackEntity c
            SET c.state = 2,
                c.versionId = :versionId,
                c.errorMessage = null,
                c.updatedAt = :updatedAt
            WHERE c.teamId = :teamId
              AND c.fileId = :fileId
              AND c.callbackKey = :callbackKey
              AND c.status = :status
              AND c.contentUrlHash = :contentUrlHash
            """)
    int markSaved(@Param("teamId") Long teamId,
                  @Param("fileId") Long fileId,
                  @Param("callbackKey") String callbackKey,
                  @Param("status") Integer status,
                  @Param("contentUrlHash") String contentUrlHash,
                  @Param("versionId") Long versionId,
                  @Param("updatedAt") LocalDateTime updatedAt);

    @Modifying
    @Query("""
            UPDATE OnlyOfficeCallbackEntity c
            SET c.state = 2,
                c.versionId = :versionId,
                c.errorMessage = null,
                c.lastAttemptAt = :updatedAt,
                c.updatedAt = :updatedAt
            WHERE c.id = :callbackId
            """)
    int markSavedById(@Param("callbackId") Long callbackId,
                      @Param("versionId") Long versionId,
                      @Param("updatedAt") LocalDateTime updatedAt);

    @Modifying
    @Query("""
            UPDATE OnlyOfficeCallbackEntity c
            SET c.state = 3,
                c.errorMessage = :errorMessage,
                c.lastAttemptAt = :updatedAt,
                c.updatedAt = :updatedAt
            WHERE c.id = :callbackId
            """)
    int markFailedById(@Param("callbackId") Long callbackId,
                       @Param("errorMessage") String errorMessage,
                       @Param("updatedAt") LocalDateTime updatedAt);

    @Modifying
    @Query("""
            UPDATE OnlyOfficeCallbackEntity c
            SET c.state = 1,
                c.errorMessage = null,
                c.updatedAt = :updatedAt
            WHERE c.taskId = :taskId
              AND c.state = 3
            """)
    int resetFailedByTaskId(@Param("taskId") Long taskId,
                            @Param("updatedAt") LocalDateTime updatedAt);

    void deleteByTeamIdAndFileIdAndCallbackKeyAndStatusAndContentUrlHash(
            Long teamId, Long fileId, String callbackKey, Integer status, String contentUrlHash);
}
