package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long>, JpaSpecificationExecutor<UserEntity> {

    Optional<UserEntity> findByUsername(String username);

    Optional<UserEntity> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    @Modifying
    @Query("UPDATE UserEntity u SET u.storageUsed = u.storageUsed + :delta WHERE u.id = :userId")
    int updateStorageUsed(@Param("userId") Long userId, @Param("delta") long delta);

    @Query("SELECT COALESCE(SUM(u.storageUsed), 0) FROM UserEntity u WHERE u.deletedAt IS NULL")
    Long selectTotalStorageUsed();
}
