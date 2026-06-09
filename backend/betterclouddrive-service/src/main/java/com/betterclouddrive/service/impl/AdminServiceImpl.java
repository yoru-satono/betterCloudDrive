package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.mapper.UserMapper;
import com.betterclouddrive.service.AdminService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final UserMapper userMapper;

    @Override
    public PageResult<UserEntity> listUsers(String keyword, Integer status, int page, int size) {
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<UserEntity>()
                .isNull(UserEntity::getDeletedAt)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(UserEntity::getUsername, keyword)
                        .or().like(UserEntity::getEmail, keyword)
                        .or().like(UserEntity::getNickname, keyword))
                .eq(status != null, UserEntity::getStatus, status)
                .orderByDesc(UserEntity::getCreatedAt);

        Page<UserEntity> result = userMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), page, size);
    }

    @Override
    @Transactional
    public void updateUserStatus(Long userId, Integer status) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null || user.getDeletedAt() != null) {
            throw new BusinessException(ApiCode.NOT_FOUND, "User not found");
        }
        user.setStatus(status);
        userMapper.updateById(user);
        log.info("Admin updated user {} status to {}", userId, status);
    }

    @Override
    @Transactional
    public void updateUserQuota(Long userId, Long storageQuota) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null || user.getDeletedAt() != null) {
            throw new BusinessException(ApiCode.NOT_FOUND, "User not found");
        }
        user.setStorageQuota(storageQuota);
        userMapper.updateById(user);
        log.info("Admin updated user {} quota to {}", userId, storageQuota);
    }

    @Override
    public Map<String, Object> getSystemStats() {
        long totalUsers = userMapper.selectCount(
                new LambdaQueryWrapper<UserEntity>().isNull(UserEntity::getDeletedAt));
        long activeUsers = userMapper.selectCount(
                new LambdaQueryWrapper<UserEntity>()
                        .eq(UserEntity::getStatus, 1)
                        .isNull(UserEntity::getDeletedAt));
        long totalStorageUsed = userMapper.selectTotalStorageUsed();

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalUsers", totalUsers);
        stats.put("activeUsers", activeUsers);
        stats.put("totalStorageUsed", totalStorageUsed);
        return stats;
    }
}
