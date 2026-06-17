package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.OperationLogEntity;
import com.betterclouddrive.dal.repository.OperationLogRepository;
import com.betterclouddrive.service.OperationLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogServiceImpl implements OperationLogService {

    private final OperationLogRepository operationLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Async
    public void logAsync(Long userId, String actionType, String targetType, Long targetId, String detail, String ipAddress, String userAgent) {
        logRequestAudit(userId, actionType, targetType, targetId, detail, ipAddress, userAgent,
                1, null, null, null, null, null);
    }

    @Override
    @Async("operationLogExecutor")
    public void logRequestAudit(Long userId, String actionType, String targetType, Long targetId,
                                String detail, String ipAddress, String userAgent, Integer result,
                                Integer durationMs, String requestId, String traceId,
                                Integer statusCode, Integer errorCode) {
        try {
            OperationLogEntity logEntity = OperationLogEntity.builder()
                    .userId(userId)
                    .actionType(actionType)
                    .targetType(targetType)
                    .targetId(targetId)
                    .detail(toJsonDetail(detail))
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .result(result != null ? result : 1)
                    .durationMs(durationMs)
                    .requestId(requestId)
                    .traceId(traceId)
                    .statusCode(statusCode)
                    .errorCode(errorCode)
                    .createdAt(LocalDateTime.now())
                    .build();
            operationLogRepository.save(logEntity);
        } catch (Exception e) {
            log.warn("Failed to write operation log", e);
        }
    }

    private String toJsonDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        try {
            objectMapper.readTree(detail);
            return detail;
        } catch (Exception ignored) {
            try {
                return objectMapper.writeValueAsString(Map.of("message", detail));
            } catch (Exception e) {
                return "{\"message\":\"operation\"}";
            }
        }
    }

    @Override
    @Async
    public void logSuccess(Long userId, String actionType, String targetType, Long targetId, String detail) {
        logAsync(userId, actionType, targetType, targetId, detail, null, null);
    }

    @Override
    public PageResult<OperationLogEntity> listLogs(Long userId, String actionType,
            String requestId, String traceId, Integer statusCode, Integer result,
            LocalDateTime startDate, LocalDateTime endDate, int page, int size) {
        Specification<OperationLogEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (userId != null) {
                predicates.add(cb.equal(root.get("userId"), userId));
            }
            if (actionType != null && !actionType.isBlank()) {
                predicates.add(cb.equal(root.get("actionType"), actionType));
            }
            if (requestId != null && !requestId.isBlank()) {
                predicates.add(cb.equal(root.get("requestId"), requestId));
            }
            if (traceId != null && !traceId.isBlank()) {
                predicates.add(cb.equal(root.get("traceId"), traceId));
            }
            if (statusCode != null) {
                predicates.add(cb.equal(root.get("statusCode"), statusCode));
            }
            if (result != null) {
                predicates.add(cb.equal(root.get("result"), result));
            }
            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startDate));
            }
            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), endDate));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<OperationLogEntity> logPage = operationLogRepository.findAll(spec, pageable);
        return PageResult.of(logPage.getContent(), logPage.getTotalElements(), page, size);
    }
}
