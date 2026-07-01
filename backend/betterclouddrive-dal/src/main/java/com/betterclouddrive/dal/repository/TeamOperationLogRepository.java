package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.TeamOperationLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TeamOperationLogRepository extends JpaRepository<TeamOperationLogEntity, Long>, JpaSpecificationExecutor<TeamOperationLogEntity> {
}
