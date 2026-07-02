package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.OnlyOfficeCallbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface OnlyOfficeCallbackRepository extends JpaRepository<OnlyOfficeCallbackEntity, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO onlyoffice_callbacks (
                team_id, file_id, callback_key, status, content_url_hash, state, created_at, updated_at
            ) VALUES (
                :teamId, :fileId, :callbackKey, :status, :contentUrlHash, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            ON CONFLICT (team_id, file_id, callback_key, status, content_url_hash) DO NOTHING
            """, nativeQuery = true)
    int insertProcessing(@Param("teamId") Long teamId,
                         @Param("fileId") Long fileId,
                         @Param("callbackKey") String callbackKey,
                         @Param("status") Integer status,
                         @Param("contentUrlHash") String contentUrlHash);

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

    void deleteByTeamIdAndFileIdAndCallbackKeyAndStatusAndContentUrlHash(
            Long teamId, Long fileId, String callbackKey, Integer status, String contentUrlHash);
}
