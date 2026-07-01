package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.TeamInviteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamInviteRepository extends JpaRepository<TeamInviteEntity, Long> {

    Optional<TeamInviteEntity> findByToken(String token);

    Optional<TeamInviteEntity> findByIdAndTeamId(Long id, Long teamId);

    Optional<TeamInviteEntity> findByTeamIdAndEmailAndStatus(Long teamId, String email, String status);

    List<TeamInviteEntity> findByTeamIdOrderByCreatedAtDesc(Long teamId);
}
