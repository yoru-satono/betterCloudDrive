package com.betterclouddrive.service;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.UserEntity;

import java.util.Map;

public interface AdminService {
    /** List users with optional keyword and status filters */
    PageResult<UserEntity> listUsers(String keyword, Integer status, int page, int size);

    /** Enable or disable a user (1=active, 0=disabled) */
    void updateUserStatus(Long userId, Integer status);

    /** Change a user's storage quota (bytes) */
    void updateUserQuota(Long userId, Long storageQuota);

    /** Get system-level statistics */
    Map<String, Object> getSystemStats();
}
