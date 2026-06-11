package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.service.AdminService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final UserRepository userRepository;

    @Override
    public PageResult<UserEntity> listUsers(String keyword, Integer status, int page, int size) {
        Specification<UserEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isNull(root.get("deletedAt")));
            if (keyword != null && !keyword.isBlank()) {
                String pattern = "%" + keyword + "%";
                predicates.add(cb.or(
                        cb.like(root.get("username"), pattern),
                        cb.like(root.get("email"), pattern),
                        cb.like(root.get("nickname"), pattern)
                ));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<UserEntity> result = userRepository.findAll(spec, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
    }

    @Override
    @Transactional
    public void updateUserStatus(Long userId, Integer status) {
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getDeletedAt() != null) {
            throw new BusinessException(ApiCode.NOT_FOUND, "User not found");
        }
        user.setStatus(status);
        userRepository.save(user);
        log.info("Admin updated user {} status to {}", userId, status);
    }

    @Override
    @Transactional
    public void updateUserQuota(Long userId, Long storageQuota) {
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getDeletedAt() != null) {
            throw new BusinessException(ApiCode.NOT_FOUND, "User not found");
        }
        user.setStorageQuota(storageQuota);
        userRepository.save(user);
        log.info("Admin updated user {} quota to {}", userId, storageQuota);
    }

    @Override
    public Map<String, Object> getSystemStats() {
        Specification<UserEntity> allSpec = (root, q, cb) -> cb.isNull(root.get("deletedAt"));
        Specification<UserEntity> activeSpec = (root, q, cb) -> cb.and(
                cb.isNull(root.get("deletedAt")),
                cb.equal(root.get("status"), 1)
        );

        long totalUsers = userRepository.count(allSpec);
        long activeUsers = userRepository.count(activeSpec);
        long totalStorageUsed = userRepository.selectTotalStorageUsed();

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalUsers", totalUsers);
        stats.put("activeUsers", activeUsers);
        stats.put("totalStorageUsed", totalStorageUsed);
        return stats;
    }
}
