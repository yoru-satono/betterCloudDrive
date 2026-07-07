package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.TeamTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TeamTaskRepository extends JpaRepository<TeamTaskEntity, Long>, JpaSpecificationExecutor<TeamTaskEntity> {
}
