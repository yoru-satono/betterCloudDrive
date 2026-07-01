package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.TeamFilePermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamFilePermissionRepository extends JpaRepository<TeamFilePermissionEntity, Long> {

    List<TeamFilePermissionEntity> findByTeamIdAndFileIdOrderByIdAsc(Long teamId, Long fileId);

    Optional<TeamFilePermissionEntity> findByTeamIdAndFileIdAndSubjectTypeAndSubjectUserId(
            Long teamId, Long fileId, String subjectType, Long subjectUserId);

    Optional<TeamFilePermissionEntity> findByTeamIdAndFileIdAndSubjectTypeAndSubjectRole(
            Long teamId, Long fileId, String subjectType, String subjectRole);
}
