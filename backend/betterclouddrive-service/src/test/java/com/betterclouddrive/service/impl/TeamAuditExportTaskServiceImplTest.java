package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.TeamMemberEntity;
import com.betterclouddrive.dal.entity.TeamOperationLogEntity;
import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamService;
import com.betterclouddrive.service.TeamTaskService;
import com.betterclouddrive.service.csv.TeamAuditCsvService;
import com.betterclouddrive.service.dto.TeamAuditExportPayload;
import com.betterclouddrive.service.dto.TeamTaskDownload;
import com.betterclouddrive.service.dto.TeamTaskResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamAuditExportTaskServiceImplTest {

    @Mock private TeamService teamService;
    @Mock private TeamAuditService teamAuditService;
    @Mock private TeamTaskService teamTaskService;
    @Mock private TeamAuditCsvService teamAuditCsvService;
    @Mock private TeamTaskResultStorageService teamTaskResultStorageService;

    private ObjectMapper objectMapper;
    private TeamAuditExportTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new TeamAuditExportTaskServiceImpl(
                teamService,
                teamAuditService,
                teamTaskService,
                teamAuditCsvService,
                teamTaskResultStorageService,
                objectMapper);
    }

    @Test
    void queueExport_shouldRequireManagePermissionAndStoreFiltersInPayload() throws Exception {
        when(teamService.requireManageMembers(2L, 10L))
                .thenReturn(TeamMemberEntity.builder().teamId(10L).userId(2L).role("ADMIN").build());
        TeamTaskEntity task = TeamTaskEntity.builder().id(77L).build();
        when(teamTaskService.queueTask(eq(10L), eq(2L), eq("AUDIT_EXPORT"), eq("TEAM"), eq(10L), eq("导出团队审计日志"), any()))
                .thenReturn(task);
        LocalDateTime startAt = LocalDateTime.of(2026, 7, 9, 12, 0);
        LocalDateTime endAt = startAt.plusHours(1);

        TeamTaskEntity queued = service.queueExport(2L, 10L, 5L, " file_delete ", " file ", 1, startAt, endAt);

        assertThat(queued).isSameAs(task);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(teamTaskService).queueTask(eq(10L), eq(2L), eq("AUDIT_EXPORT"), eq("TEAM"), eq(10L), eq("导出团队审计日志"), payloadCaptor.capture());
        TeamAuditExportPayload payload = objectMapper.readValue(payloadCaptor.getValue(), TeamAuditExportPayload.class);
        assertThat(payload.requesterUserId()).isEqualTo(2L);
        assertThat(payload.teamId()).isEqualTo(10L);
        assertThat(payload.actorUserId()).isEqualTo(5L);
        assertThat(payload.action()).isEqualTo("FILE_DELETE");
        assertThat(payload.targetType()).isEqualTo("FILE");
        assertThat(payload.result()).isEqualTo(1);
        assertThat(payload.startAt()).isEqualTo("2026-07-09T12:00");
        assertThat(payload.endAt()).isEqualTo("2026-07-09T13:00");
        assertThat(payload.limit()).isEqualTo(10000);
    }

    @Test
    void processPendingExports_shouldGenerateCsvAndMarkTaskSucceeded() throws Exception {
        LocalDateTime startAt = LocalDateTime.of(2026, 7, 9, 12, 0);
        LocalDateTime endAt = startAt.plusHours(1);
        TeamAuditExportPayload payload = new TeamAuditExportPayload(
                2L,
                10L,
                null,
                null,
                null,
                null,
                "2026-07-09T12:00",
                "2026-07-09T13:00",
                10000);
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(77L)
                .teamId(10L)
                .taskType("AUDIT_EXPORT")
                .status(TeamTaskEntity.STATUS_PENDING)
                .payload(objectMapper.writeValueAsString(payload))
                .build();
        TeamOperationLogEntity log = TeamOperationLogEntity.builder().id(1L).teamId(10L).action("FILE_DELETE").build();
        TeamTaskResult result = new TeamTaskResult(
                "audit-exports/77.csv",
                "team-10-audit.csv",
                "text/csv;charset=UTF-8",
                18,
                LocalDateTime.now().plusHours(1));
        when(teamTaskService.listPendingTasks("AUDIT_EXPORT", 5)).thenReturn(List.of(task));
        when(teamAuditService.exportLogs(2L, 10L, null, null, null, null, startAt, endAt, 10000))
                .thenReturn(PageResult.of(List.of(log), 1, 1, 10000));
        when(teamAuditCsvService.teamAuditCsv(List.of(log))).thenReturn("csv");
        when(teamAuditCsvService.teamAuditFileName(10L)).thenReturn("team-10-audit.csv");
        when(teamTaskResultStorageService.saveAuditExport(77L, "team-10-audit.csv", "csv")).thenReturn(result);

        int processed = service.processPendingExports(5);

        assertThat(processed).isEqualTo(1);
        verify(teamTaskService).markRunning(77L);
        verify(teamTaskService).markSucceeded(77L, "导出 1 条审计日志", result);
    }

    @Test
    void processPendingExports_shouldMarkTaskFailedWhenPayloadIsInvalid() {
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(77L)
                .teamId(10L)
                .taskType("AUDIT_EXPORT")
                .status(TeamTaskEntity.STATUS_PENDING)
                .payload("{")
                .build();
        when(teamTaskService.listPendingTasks("AUDIT_EXPORT", 5)).thenReturn(List.of(task));

        int processed = service.processPendingExports(5);

        assertThat(processed).isEqualTo(1);
        verify(teamTaskService).markRunning(77L);
        verify(teamTaskService).markFailed(eq(77L), any());
    }

    @Test
    void downloadExportResult_shouldRequireSucceededAuditExportTask() {
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(77L)
                .teamId(10L)
                .taskType("AUDIT_EXPORT")
                .status(TeamTaskEntity.STATUS_SUCCEEDED)
                .resultPath("audit-exports/77.csv")
                .build();
        TeamTaskDownload download = new TeamTaskDownload(
                "team-10-audit.csv",
                "text/csv;charset=UTF-8",
                3,
                new ByteArrayInputStream("csv".getBytes()));
        when(teamService.requireManageMembers(2L, 10L))
                .thenReturn(TeamMemberEntity.builder().teamId(10L).userId(2L).role("ADMIN").build());
        when(teamTaskService.getTask(10L, 77L)).thenReturn(task);
        when(teamTaskResultStorageService.open(task)).thenReturn(download);

        assertThat(service.downloadExportResult(2L, 10L, 77L)).isSameAs(download);
    }

    @Test
    void downloadExportResult_shouldRejectRunningTask() {
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(77L)
                .teamId(10L)
                .taskType("AUDIT_EXPORT")
                .status(TeamTaskEntity.STATUS_RUNNING)
                .build();
        when(teamService.requireManageMembers(2L, 10L))
                .thenReturn(TeamMemberEntity.builder().teamId(10L).userId(2L).role("ADMIN").build());
        when(teamTaskService.getTask(10L, 77L)).thenReturn(task);

        assertThatThrownBy(() -> service.downloadExportResult(2L, 10L, 77L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void cleanupExpiredResults_shouldDeleteFilesAndClearMetadata() {
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(77L)
                .resultMessage("导出 1 条审计日志")
                .resultPath("audit-exports/77.csv")
                .build();
        when(teamTaskService.listExpiredResults(any())).thenReturn(List.of(task));
        when(teamTaskResultStorageService.delete(task)).thenReturn(true);

        int cleaned = service.cleanupExpiredResults();

        assertThat(cleaned).isEqualTo(1);
        verify(teamTaskService).clearResult(77L, "导出 1 条审计日志；结果文件已过期清理");
    }
}
