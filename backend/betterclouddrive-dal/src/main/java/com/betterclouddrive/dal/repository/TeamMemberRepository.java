package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.TeamMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface TeamMemberRepository extends JpaRepository<TeamMemberEntity, Long> {

    Optional<TeamMemberEntity> findByTeamIdAndUserId(Long teamId, Long userId);

    Optional<TeamMemberEntity> findByTeamIdAndUserIdAndStatus(Long teamId, Long userId, Integer status);

    List<TeamMemberEntity> findByTeamIdAndStatusOrderByCreatedAtAsc(Long teamId, Integer status);

    Page<TeamMemberEntity> findByTeamIdAndStatusOrderByCreatedAtAsc(Long teamId, Integer status, Pageable pageable);

    long countByTeamIdAndStatus(Long teamId, Integer status);
}
