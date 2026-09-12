package com.betterclouddrive.web.dto.response;

import com.betterclouddrive.dal.entity.TeamTaskEntity;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class TeamTaskResponse {

    private Long id;
    private Long teamId;
    private Long actorUserId;
    private String taskType;
    private String status;
    private String targetType;
    private Long targetId;
    private String titleKey;
    private Map<String, Object> titleParams;
    private Integer progress;
    private String resultMessageKey;
    private Map<String, Object> resultMessageParams;
    private String resultFileName;
    private String resultContentType;
    private Long resultSizeBytes;
    private LocalDateTime resultExpiresAt;
    private String errorMessageKey;
    private Map<String, Object> errorMessageParams;
    private Integer retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    public static TeamTaskResponse from(TeamTaskEntity task) {
        return TeamTaskResponse.builder()
                .id(task.getId())
                .teamId(task.getTeamId())
                .actorUserId(task.getActorUserId())
                .taskType(task.getTaskType())
                .status(task.getStatus())
                .targetType(task.getTargetType())
                .targetId(task.getTargetId())
                .titleKey(task.getTitleKey())
                .titleParams(task.getTitleParams() == null ? Map.of() : task.getTitleParams())
                .progress(task.getProgress())
                .resultMessageKey(task.getResultMessageKey())
                .resultMessageParams(task.getResultMessageParams() == null ? Map.of() : task.getResultMessageParams())
                .resultFileName(task.getResultFileName())
                .resultContentType(task.getResultContentType())
                .resultSizeBytes(task.getResultSizeBytes())
                .resultExpiresAt(task.getResultExpiresAt())
                .errorMessageKey(task.getErrorMessageKey())
                .errorMessageParams(task.getErrorMessageParams() == null ? Map.of() : task.getErrorMessageParams())
                .retryCount(task.getRetryCount())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .build();
    }
}
