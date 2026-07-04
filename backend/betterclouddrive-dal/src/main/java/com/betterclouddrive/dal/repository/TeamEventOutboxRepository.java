package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.TeamEventOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TeamEventOutboxRepository extends JpaRepository<TeamEventOutboxEntity, Long>, JpaSpecificationExecutor<TeamEventOutboxEntity> {
}
