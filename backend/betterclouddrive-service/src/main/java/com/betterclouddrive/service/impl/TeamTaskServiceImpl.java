package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.dal.repository.TeamTaskRepository;
import com.betterclouddrive.service.TeamTaskService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class TeamTaskServiceImpl implements TeamTaskService {

    private final TeamTaskRepository teamTaskRepository;

    @Override
    public PageResult<TeamTaskEntity> listTasks(Long teamId, String status, String taskType, int page, int size) {
        Specification<TeamTaskEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("teamId"), teamId));
            if (StringUtils.hasText(status)) {
                predicates.add(cb.equal(root.get("status"), normalize(status)));
            }
            if (StringUtils.hasText(taskType)) {
                predicates.add(cb.equal(root.get("taskType"), normalize(taskType)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<TeamTaskEntity> result = teamTaskRepository.findAll(
                spec,
                PageRequest.of(Math.max(page - 1, 0), size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
    }

    @Override
    @Transactional
    public TeamTaskEntity startTask(Long teamId, Long actorUserId, String taskType, String targetType, Long targetId, String title) {
        LocalDateTime now = LocalDateTime.now();
        return teamTaskRepository.save(TeamTaskEntity.builder()
                .teamId(teamId)
                .actorUserId(actorUserId)
                .taskType(normalizeRequired(taskType, "Task type is required"))
                .status(TeamTaskEntity.STATUS_RUNNING)
                .targetType(StringUtils.hasText(targetType) ? normalize(targetType) : null)
                .targetId(targetId)
                .title(StringUtils.hasText(title) ? title.trim() : normalizeRequired(taskType, "Task type is required"))
                .progress(0)
                .retryCount(0)
                .createdAt(now)
                .updatedAt(now)
                .startedAt(now)
                .build());
    }

    @Override
    @Transactional
    public void markSucceeded(Long taskId, String resultMessage) {
        if (taskId == null) {
            return;
        }
        TeamTaskEntity task = teamTaskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        task.setStatus(TeamTaskEntity.STATUS_SUCCEEDED);
        task.setProgress(100);
        task.setResultMessage(resultMessage);
        task.setErrorMessage(null);
        task.setCompletedAt(LocalDateTime.now());
        teamTaskRepository.save(task);
    }

    @Override
    @Transactional
    public void markFailed(Long taskId, String errorMessage) {
        if (taskId == null) {
            return;
        }
        TeamTaskEntity task = teamTaskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        task.setStatus(TeamTaskEntity.STATUS_FAILED);
        task.setErrorMessage(StringUtils.hasText(errorMessage) ? errorMessage.trim() : "Task failed");
        task.setCompletedAt(LocalDateTime.now());
        teamTaskRepository.save(task);
    }

    @Override
    @Transactional
    public TeamTaskEntity retryTask(Long teamId, Long taskId) {
        TeamTaskEntity task = teamTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "Team task not found"));
        if (!teamId.equals(task.getTeamId())) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Team task not found");
        }
        if (!TeamTaskEntity.STATUS_FAILED.equals(task.getStatus())) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Only failed tasks can be retried");
        }
        task.setStatus(TeamTaskEntity.STATUS_PENDING);
        task.setProgress(0);
        task.setErrorMessage(null);
        task.setRetryCount((task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1);
        task.setStartedAt(null);
        task.setCompletedAt(null);
        task.setUpdatedAt(LocalDateTime.now());
        return teamTaskRepository.save(task);
    }

    private String normalizeRequired(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, message);
        }
        return normalize(value);
    }

    private String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
