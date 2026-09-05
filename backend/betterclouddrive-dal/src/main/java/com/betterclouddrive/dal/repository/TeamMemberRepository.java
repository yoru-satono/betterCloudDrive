package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.TeamMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TeamMemberRepository extends JpaRepository<TeamMemberEntity, Long> {

    Optional<TeamMemberEntity> findByTeamIdAndUserId(Long teamId, Long userId);

    Optional<TeamMemberEntity> findByTeamIdAndUserIdAndStatus(Long teamId, Long userId, Integer status);

    List<TeamMemberEntity> findByTeamIdAndStatusOrderByCreatedAtAsc(Long teamId, Integer status);

    Page<TeamMemberEntity> findByTeamIdAndStatusOrderByCreatedAtAsc(Long teamId, Integer status, Pageable pageable);

    @Query(value = """
            select member from TeamMemberEntity member, UserEntity user
            where member.userId = user.id
              and member.teamId = :teamId
              and member.status = :status
            order by case member.role
                when 'OWNER' then 0
                when 'ADMIN' then 1
                when 'EDITOR' then 2
                else 3 end,
                user.username asc,
                member.userId asc
            """,
            countQuery = """
            select count(member) from TeamMemberEntity member
            where member.teamId = :teamId and member.status = :status
            """)
    Page<TeamMemberEntity> findActiveMembersStable(
            @Param("teamId") Long teamId,
            @Param("status") Integer status,
            Pageable pageable);

    long countByTeamIdAndStatus(Long teamId, Integer status);
}
