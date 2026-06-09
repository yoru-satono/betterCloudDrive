package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.OperationLogEntity;
import com.betterclouddrive.dal.mapper.OperationLogMapper;
import com.betterclouddrive.service.OperationLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogServiceImpl implements OperationLogService {

    private final OperationLogMapper operationLogMapper;

    @Override
    @Async
    public void logAsync(Long userId, String actionType, String targetType, Long targetId, String detail, String ipAddress, String userAgent) {
        try {
            OperationLogEntity log = OperationLogEntity.builder()
                    .userId(userId)
                    .actionType(actionType)
                    .targetType(targetType)
                    .targetId(targetId)
                    .detail(detail)
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .result(1)
                    .createdAt(LocalDateTime.now())
                    .build();
            operationLogMapper.insert(log);
        } catch (Exception e) {
            log.warn("Failed to write operation log", e);
        }
    }

    @Override
    @Async
    public void logSuccess(Long userId, String actionType, String targetType, Long targetId, String detail) {
        logAsync(userId, actionType, targetType, targetId, detail, null, null);
    }

    @Override
    public PageResult<OperationLogEntity> listLogs(Long userId, String actionType,
            LocalDateTime startDate, LocalDateTime endDate, int page, int size) {
        LambdaQueryWrapper<OperationLogEntity> wrapper = new LambdaQueryWrapper<OperationLogEntity>()
                .eq(userId != null, OperationLogEntity::getUserId, userId)
                .eq(actionType != null && !actionType.isBlank(), OperationLogEntity::getActionType, actionType)
                .ge(startDate != null, OperationLogEntity::getCreatedAt, startDate)
                .le(endDate != null, OperationLogEntity::getCreatedAt, endDate)
                .orderByDesc(OperationLogEntity::getCreatedAt);

        Page<OperationLogEntity> result = operationLogMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), page, size);
    }
}
