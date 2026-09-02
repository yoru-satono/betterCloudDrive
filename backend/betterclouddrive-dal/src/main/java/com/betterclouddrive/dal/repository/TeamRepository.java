package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.TeamEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<TeamEntity, Long>, JpaSpecificationExecutor<TeamEntity> {

    Optional<TeamEntity> findByIdAndDeletedAtIsNull(Long id);

    List<TeamEntity> findByDeletedAtIsNull();

    @Query("""
            SELECT t FROM TeamEntity t
            WHERE t.deletedAt IS NOT NULL
              AND (
                (t.purgeAfter IS NOT NULL AND t.purgeAfter <= :purgeCutoff)
                OR (t.purgeAfter IS NULL AND t.deletedAt <= :legacyDeletedCutoff)
              )
            ORDER BY t.id ASC
            """)
    List<TeamEntity> findExpiredDeletedTeams(
            @Param("purgeCutoff") LocalDateTime purgeCutoff,
            @Param("legacyDeletedCutoff") LocalDateTime legacyDeletedCutoff,
            Pageable pageable);

    @Query("""
            SELECT t FROM TeamEntity t
            JOIN TeamMemberEntity tm ON tm.teamId = t.id
            WHERE tm.userId = :userId AND tm.status = 1 AND t.deletedAt IS NULL AND t.archivedAt IS NULL
            ORDER BY t.name ASC, t.id ASC
            """)
    Page<TeamEntity> findActiveTeamsForUser(@Param("userId") Long userId, Pageable pageable);

    @Modifying
    @Query("UPDATE TeamEntity t SET t.storageUsed = t.storageUsed + :delta WHERE t.id = :teamId")
    int updateStorageUsed(@Param("teamId") Long teamId, @Param("delta") long delta);

    @Modifying
    @Query("DELETE FROM TeamEntity t WHERE t.id = :teamId")
    int deleteExistingById(@Param("teamId") Long teamId);
}
