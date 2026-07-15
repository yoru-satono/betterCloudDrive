package com.betterclouddrive.service;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.service.dto.TeamTaskResult;

import java.time.LocalDateTime;
import java.util.List;

public interface TeamTaskService {

    PageResult<TeamTaskEntity> listTasks(Long requesterUserId, Long teamId, String status, String taskType, int page, int size);

    TeamTaskEntity startTask(Long teamId, Long actorUserId, String taskType, String targetType, Long targetId, String title);

    TeamTaskEntity queueTask(Long teamId, Long actorUserId, String taskType, String targetType, Long targetId, String title);

    TeamTaskEntity queueTask(Long teamId, Long actorUserId, String taskType, String targetType, Long targetId, String title, String payload);

    TeamTaskEntity getTask(Long teamId, Long taskId);

    TeamTaskEntity getTaskForUser(Long requesterUserId, Long teamId, Long taskId);

    List<TeamTaskEntity> listPendingTasks(String taskType, int limit);

    void markRunning(Long taskId);

    void markSucceeded(Long taskId, String resultMessage);

    void markSucceeded(Long taskId, String resultMessage, TeamTaskResult result);

    void markFailed(Long taskId, String errorMessage);

    void clearResult(Long taskId, String resultMessage);

    List<TeamTaskEntity> listExpiredResults(LocalDateTime now);

    TeamTaskEntity retryTask(Long requesterUserId, Long teamId, Long taskId);
}
