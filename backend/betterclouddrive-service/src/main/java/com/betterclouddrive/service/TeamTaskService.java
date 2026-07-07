package com.betterclouddrive.service;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.TeamTaskEntity;

public interface TeamTaskService {

    PageResult<TeamTaskEntity> listTasks(Long teamId, String status, String taskType, int page, int size);

    TeamTaskEntity startTask(Long teamId, Long actorUserId, String taskType, String targetType, Long targetId, String title);

    void markSucceeded(Long taskId, String resultMessage);

    void markFailed(Long taskId, String errorMessage);

    TeamTaskEntity retryTask(Long teamId, Long taskId);
}
