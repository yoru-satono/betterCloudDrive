package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.TeamOperationLogEntity;
import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.service.TeamAuditExportTaskService;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamService;
import com.betterclouddrive.service.TeamTaskService;
import com.betterclouddrive.service.csv.TeamAuditCsvService;
import com.betterclouddrive.service.dto.TeamAuditExportPayload;
import com.betterclouddrive.service.dto.TeamTaskDownload;
import com.betterclouddrive.service.dto.TeamTaskResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class TeamAuditExportTaskServiceImpl implements TeamAuditExportTaskService {

    private static final int AUDIT_EXPORT_LIMIT = 10000;
    public static final String TASK_TYPE_AUDIT_EXPORT = "AUDIT_EXPORT";

    private final TeamService teamService;
    private final TeamAuditService teamAuditService;
    private final TeamTaskService teamTaskService;
    private final TeamAuditCsvService teamAuditCsvService;
    private final TeamTaskResultStorageService teamTaskResultStorageService;
    private final ObjectMapper objectMapper;

    @Override
    public TeamTaskEntity queueExport(
            Long requesterUserId,
            Long teamId,
            Long actorUserId,
            String action,
            String targetType,
            Integer result,
            java.time.LocalDateTime startAt,
            java.time.LocalDateTime endAt) {
        teamService.requireManageMembers(requesterUserId, teamId);
        TeamAuditExportPayload payload = new TeamAuditExportPayload(
                requesterUserId,
                teamId,
                actorUserId,
                normalize(action),
                normalize(targetType),
                result,
                formatDateTime(startAt),
                formatDateTime(endAt),
                AUDIT_EXPORT_LIMIT);
        return teamTaskService.queueTask(
                teamId,
                requesterUserId,
                TASK_TYPE_AUDIT_EXPORT,
                "TEAM",
                teamId,
                "Export team audit log",
                toJson(payload));
    }

    @Override
    public int processPendingExports(int batchSize) {
        int processed = 0;
        for (TeamTaskEntity task : teamTaskService.listPendingTasks(TASK_TYPE_AUDIT_EXPORT, batchSize)) {
            processTask(task);
            processed++;
        }
        return processed;
    }

    @Override
    public TeamTaskDownload downloadExportResult(Long requesterUserId, Long teamId, Long taskId) {
        teamService.requireManageMembers(requesterUserId, teamId);
        TeamTaskEntity task = teamTaskService.getTask(teamId, taskId);
        if (!TASK_TYPE_AUDIT_EXPORT.equals(task.getTaskType())) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Task result is not available");
        }
        if (!TeamTaskEntity.STATUS_SUCCEEDED.equals(task.getStatus())) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Task has not succeeded");
        }
        return teamTaskResultStorageService.open(task);
    }

    @Override
    public int cleanupExpiredResults() {
        int cleaned = 0;
        for (TeamTaskEntity task : teamTaskService.listExpiredResults(java.time.LocalDateTime.now())) {
            if (teamTaskResultStorageService.delete(task)) {
                cleaned++;
            }
            teamTaskService.clearResult(task.getId(), "task.result_expired");
        }
        return cleaned;
    }

    private void processTask(TeamTaskEntity task) {
        teamTaskService.markRunning(task.getId());
        try {
            TeamAuditExportPayload payload = readPayload(task);
            PageResult<TeamOperationLogEntity> logs = teamAuditService.exportLogs(
                    payload.requesterUserId(),
                    payload.teamId(),
                    payload.actorUserId(),
                    payload.action(),
                    payload.targetType(),
                    payload.result(),
                    parseDateTime(payload.startAt()),
                    parseDateTime(payload.endAt()),
                    payload.limit());
            String csv = teamAuditCsvService.teamAuditCsv(logs.getRecords());
            String fileName = teamAuditCsvService.teamAuditFileName(payload.teamId());
            TeamTaskResult result = teamTaskResultStorageService.saveAuditExport(task.getId(), fileName, csv);
            teamTaskService.markSucceeded(task.getId(), "Exported " + logs.getRecords().size() + " audit records", result);
        } catch (RuntimeException e) {
            log.warn("Failed to process team audit export task {}", task.getId(), e);
            teamTaskService.markFailed(task.getId(), StringUtils.hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private TeamAuditExportPayload readPayload(TeamTaskEntity task) {
        if (!StringUtils.hasText(task.getPayload())) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Audit export task payload is missing");
        }
        try {
            return objectMapper.readValue(task.getPayload(), TeamAuditExportPayload.class);
        } catch (JacksonException e) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Audit export task payload is invalid");
        }
    }

    private String toJson(TeamAuditExportPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize audit export payload", e);
        }
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase() : null;
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private LocalDateTime parseDateTime(String value) {
        return StringUtils.hasText(value) ? LocalDateTime.parse(value) : null;
    }

    private String appendExpiredMessage(String resultMessage) {
        String prefix = StringUtils.hasText(resultMessage) ? resultMessage.trim() : "Task result";
        return prefix + "; expired result file removed";
    }
}
