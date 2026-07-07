package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.TeamOperationLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface TeamOperationLogRepository extends JpaRepository<TeamOperationLogEntity, Long>, JpaSpecificationExecutor<TeamOperationLogEntity> {

    List<TeamOperationLogEntity> findTop5ByTeamIdOrderByCreatedAtDesc(Long teamId);

    Optional<TeamOperationLogEntity> findFirstByTeamIdAndActionOrderByCreatedAtDesc(Long teamId, String action);
}
