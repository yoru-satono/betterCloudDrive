package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.TeamMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamMemberRepository extends JpaRepository<TeamMemberEntity, Long> {

    Optional<TeamMemberEntity> findByTeamIdAndUserId(Long teamId, Long userId);

    Optional<TeamMemberEntity> findByTeamIdAndUserIdAndStatus(Long teamId, Long userId, Integer status);

    List<TeamMemberEntity> findByTeamIdAndStatusOrderByCreatedAtAsc(Long teamId, Integer status);
}
