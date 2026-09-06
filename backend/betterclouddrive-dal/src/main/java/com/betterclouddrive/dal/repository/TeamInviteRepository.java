package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.TeamInviteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;

import java.util.List;
import java.util.Optional;

public interface TeamInviteRepository extends JpaRepository<TeamInviteEntity, Long> {

    Optional<TeamInviteEntity> findByToken(String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM TeamInviteEntity i WHERE i.token = :token")
    Optional<TeamInviteEntity> findByTokenForUpdate(@Param("token") String token);

    Optional<TeamInviteEntity> findByIdAndTeamId(Long id, Long teamId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM TeamInviteEntity i WHERE i.id = :id AND i.teamId = :teamId")
    Optional<TeamInviteEntity> findByIdAndTeamIdForUpdate(@Param("id") Long id, @Param("teamId") Long teamId);

    Optional<TeamInviteEntity> findByTeamIdAndEmailAndStatus(Long teamId, String email, String status);

    List<TeamInviteEntity> findByTeamIdOrderByCreatedAtDesc(Long teamId);

    Page<TeamInviteEntity> findByTeamIdOrderByCreatedAtDescIdDesc(Long teamId, Pageable pageable);

    @Modifying
    @Query("""
            UPDATE TeamInviteEntity i
               SET i.status = 'EXPIRED', i.updatedAt = :now
             WHERE i.teamId = :teamId AND i.status = 'PENDING' AND i.expiresAt < :now
            """)
    int expirePending(@Param("teamId") Long teamId, @Param("now") LocalDateTime now);
}
