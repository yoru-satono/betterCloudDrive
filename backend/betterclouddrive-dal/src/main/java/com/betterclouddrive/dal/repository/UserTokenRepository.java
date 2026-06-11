package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.UserTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserTokenRepository extends JpaRepository<UserTokenEntity, Long> {

    Optional<UserTokenEntity> findByJti(String jti);
}
