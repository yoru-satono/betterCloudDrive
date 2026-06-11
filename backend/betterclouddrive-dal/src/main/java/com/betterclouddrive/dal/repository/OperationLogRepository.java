package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.OperationLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OperationLogRepository extends JpaRepository<OperationLogEntity, Long>, JpaSpecificationExecutor<OperationLogEntity> {
}
