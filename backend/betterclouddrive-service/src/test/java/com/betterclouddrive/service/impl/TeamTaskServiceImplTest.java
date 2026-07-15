package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.dal.entity.TeamMemberEntity;
import com.betterclouddrive.dal.repository.OnlyOfficeCallbackRepository;
import com.betterclouddrive.dal.repository.TeamTaskRepository;
import com.betterclouddrive.service.dto.TeamTaskResult;
import com.betterclouddrive.service.dto.TeamEffectivePermission;
import com.betterclouddrive.service.TeamPermissionService;
import com.betterclouddrive.service.TeamService;
import com.betterclouddrive.service.metrics.OnlyOfficeSaveMetrics;
import com.betterclouddrive.service.metrics.QueueMetrics;
import com.betterclouddrive.service.config.TeamTaskProperties;
import com.betterclouddrive.service.queue.TeamTaskOutboxWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class TeamTaskServiceImplTest {

    @Mock
    private TeamTaskRepository teamTaskRepository;

    @Mock
    private OnlyOfficeCallbackRepository onlyOfficeCallbackRepository;

    @Mock
    private OnlyOfficeSaveMetrics onlyOfficeSaveMetrics;

    @Mock
    private TeamService teamService;

    @Mock
    private TeamPermissionService teamPermissionService;

    @Mock
    private TeamTaskProperties teamTaskProperties;

    @Mock
    private TeamTaskOutboxWriter teamTaskOutboxWriter;

    @Mock
    private QueueMetrics queueMetrics;

    @InjectMocks
    private TeamTaskServiceImpl teamTaskService;

    @Test
    void startTask_shouldNormalizeTypeAndCreateRunningTask() {
        when(teamTaskRepository.save(any(TeamTaskEntity.class))).thenAnswer(inv -> {
            TeamTaskEntity task = inv.getArgument(0);
            task.setId(1L);
            return task;
        });

        TeamTaskEntity task = teamTaskService.startTask(
                10L, 2L, " audit_export ", " file ", 7L, " 导出审计 ");

        assertThat(task.getId()).isEqualTo(1L);
        assertThat(task.getTeamId()).isEqualTo(10L);
        assertThat(task.getActorUserId()).isEqualTo(2L);
        assertThat(task.getTaskType()).isEqualTo("AUDIT_EXPORT");
        assertThat(task.getTargetType()).isEqualTo("FILE");
        assertThat(task.getTargetId()).isEqualTo(7L);
        assertThat(task.getTitleKey()).isEqualTo("task.auditExport.title");
        assertThat(task.getStatus()).isEqualTo(TeamTaskEntity.STATUS_RUNNING);
        assertThat(task.getProgress()).isZero();
        assertThat(task.getRetryCount()).isZero();
        assertThat(task.getStartedAt()).isNotNull();
        assertThat(task.getCreatedAt()).isNotNull();
        assertThat(task.getUpdatedAt()).isNotNull();
    }

    @Test
    void queueTask_shouldCreatePendingTaskWithoutStartTime() {
        when(teamTaskRepository.save(any(TeamTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TeamTaskEntity task = teamTaskService.queueTask(10L, 2L, "onlyoffice_save", "file", 7L, "保存文档", "{\"id\":7}");

        assertThat(task.getStatus()).isEqualTo(TeamTaskEntity.STATUS_PENDING);
        verify(teamTaskOutboxWriter).enqueue(task);
        assertThat(task.getPayload()).isEqualTo("{\"id\":7}");
        assertThat(task.getStartedAt()).isNull();
        assertThat(task.getCreatedAt()).isNotNull();
    }

    @Test
    void getTask_shouldRequireMatchingTeam() {
        TeamTaskEntity task = TeamTaskEntity.builder().id(7L).teamId(10L).build();
        when(teamTaskRepository.findByIdAndTeamId(7L, 10L)).thenReturn(Optional.of(task));

        assertThat(teamTaskService.getTask(10L, 7L)).isSameAs(task);
    }

    @Test
    void claimTasks_shouldRecoverTimedOutWorkBeforeAtomicClaim() {
        TeamTaskEntity claimed = TeamTaskEntity.builder().id(7L).status(TeamTaskEntity.STATUS_RUNNING).build();
        when(teamTaskProperties.getRunningTimeoutSeconds()).thenReturn(120L);
        when(teamTaskRepository.recoverTimedOutTasks(any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(2);
        when(teamTaskRepository.claimPendingTasks("THUMBNAIL", 5)).thenReturn(List.of(claimed));

        List<TeamTaskEntity> result = teamTaskService.claimTasks(" thumbnail ", 5);

        assertThat(result).containsExactly(claimed);
        verify(teamTaskRepository).recoverTimedOutTasks(any(LocalDateTime.class), any(LocalDateTime.class));
        verify(teamTaskRepository).claimPendingTasks("THUMBNAIL", 5);
        verify(queueMetrics).recordTimedOutTaskRecovery(2);
        verify(queueMetrics).recordTaskClaim("THUMBNAIL", 1);
    }

    @Test
    void markSucceeded_shouldStoreResultMetadata() {
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(7L)
                .taskType("AUDIT_EXPORT")
                .status(TeamTaskEntity.STATUS_RUNNING)
                .progress(20)
                .build();
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(1);
        when(teamTaskRepository.findById(7L)).thenReturn(Optional.of(task));

        teamTaskService.markSucceeded(7L, "导出 3 条审计日志", new TeamTaskResult(
                "audit-exports/7.csv",
                "team-10-audit.csv",
                "text/csv;charset=UTF-8",
                128L,
                expiresAt));

        assertThat(task.getStatus()).isEqualTo(TeamTaskEntity.STATUS_SUCCEEDED);
        assertThat(task.getProgress()).isEqualTo(100);
        assertThat(task.getResultMessageKey()).isEqualTo("task.succeeded");
        assertThat(task.getResultMessageParams()).containsEntry("taskType", "AUDIT_EXPORT");
        assertThat(task.getResultPath()).isEqualTo("audit-exports/7.csv");
        assertThat(task.getResultFileName()).isEqualTo("team-10-audit.csv");
        assertThat(task.getResultContentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(task.getResultSizeBytes()).isEqualTo(128L);
        assertThat(task.getResultExpiresAt()).isEqualTo(expiresAt);
        verify(teamTaskRepository).save(task);
    }

    @Test
    void listTasks_shouldApplyPagingAndNormalizeFilters() {
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(1L)
                .teamId(10L)
                .status(TeamTaskEntity.STATUS_FAILED)
                .taskType("AUDIT_EXPORT")
                .build();
        when(teamTaskRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(task), PageRequest.of(1, 10), 11));

        when(teamService.requireRead(2L, 10L)).thenReturn(manager());
        PageResult<TeamTaskEntity> result = teamTaskService.listTasks(2L, 10L, " failed ", " audit_export ", 2, 10);

        assertThat(result.getRecords()).containsExactly(task);
        assertThat(result.getTotal()).isEqualTo(11);
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(10);
        verify(teamTaskRepository).findAll(any(Specification.class), argThat((Pageable pageable) ->
                pageable.getPageNumber() == 1
                        && pageable.getPageSize() == 10
                        && pageable.getSort().getOrderFor("createdAt") != null));
    }

    @Test
    void listTasks_shouldOnlyReturnOwnedTasksWithCurrentReadPermissionForMembers() {
        TeamTaskEntity readable = memberTask(1L, 2L, "FOLDER_ZIP");
        TeamTaskEntity revoked = memberTask(2L, 2L, "FOLDER_ZIP");
        TeamTaskEntity audit = memberTask(3L, 2L, "AUDIT_EXPORT");
        when(teamService.requireRead(2L, 10L)).thenReturn(member(TeamService.ROLE_VIEWER));
        when(teamTaskRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(readable, revoked, audit));
        when(teamPermissionService.effectivePermissions(2L, 10L, 1L)).thenReturn(TeamEffectivePermission.viewer());
        when(teamPermissionService.effectivePermissions(2L, 10L, 2L))
                .thenReturn(new TeamEffectivePermission(false, false, false, false, false));

        PageResult<TeamTaskEntity> result = teamTaskService.listTasks(2L, 10L, null, null, 1, 20);

        assertThat(result.getRecords()).containsExactly(readable);
        assertThat(result.getTotal()).isEqualTo(1);
    }

    @Test
    void getTaskForUser_shouldHideCrossUserAndManagerOnlyTaskTypes() {
        TeamTaskEntity task = memberTask(7L, 3L, "AUDIT_EXPORT");
        when(teamService.requireRead(2L, 10L)).thenReturn(member(TeamService.ROLE_EDITOR));
        when(teamTaskRepository.findByIdAndTeamId(7L, 10L)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> teamTaskService.getTaskForUser(2L, 10L, 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(404));
    }

    @Test
    void retryTask_shouldAllowOwnedFolderZipWithCurrentReadPermission() {
        TeamTaskEntity task = memberTask(7L, 2L, "FOLDER_ZIP");
        task.setStatus(TeamTaskEntity.STATUS_FAILED);
        when(teamService.requireRead(2L, 10L)).thenReturn(member(TeamService.ROLE_VIEWER));
        when(teamTaskRepository.findById(7L)).thenReturn(Optional.of(task));
        when(teamPermissionService.effectivePermissions(2L, 10L, 7L)).thenReturn(TeamEffectivePermission.viewer());
        when(teamTaskRepository.save(task)).thenReturn(task);

        assertThat(teamTaskService.retryTask(2L, 10L, 7L).getStatus()).isEqualTo(TeamTaskEntity.STATUS_PENDING);
    }

    @Test
    void retryTask_shouldRejectOnlyOfficeRetryWithoutCurrentWritePermission() {
        TeamTaskEntity task = memberTask(7L, 2L, "ONLYOFFICE_SAVE");
        task.setStatus(TeamTaskEntity.STATUS_FAILED);
        when(teamService.requireRead(2L, 10L)).thenReturn(member(TeamService.ROLE_EDITOR));
        when(teamTaskRepository.findById(7L)).thenReturn(Optional.of(task));
        when(teamPermissionService.effectivePermissions(2L, 10L, 7L)).thenReturn(TeamEffectivePermission.viewer());

        assertThatThrownBy(() -> teamTaskService.retryTask(2L, 10L, 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(404));
    }

    @Test
    void retryTask_shouldMoveFailedTaskBackToPendingAndIncrementRetryCount() {
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(7L)
                .teamId(10L)
                .status(TeamTaskEntity.STATUS_FAILED)
                .progress(40)
                .errorMessageKey("task.failed")
                .retryCount(2)
                .build();
        when(teamTaskRepository.findById(7L)).thenReturn(Optional.of(task));
        when(teamTaskRepository.save(any(TeamTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        when(teamService.requireRead(2L, 10L)).thenReturn(manager());
        TeamTaskEntity retried = teamTaskService.retryTask(2L, 10L, 7L);

        assertThat(retried.getStatus()).isEqualTo(TeamTaskEntity.STATUS_PENDING);
        assertThat(retried.getProgress()).isZero();
        assertThat(retried.getErrorMessageKey()).isNull();
        assertThat(retried.getResultMessageKey()).isNull();
        assertThat(retried.getResultPath()).isNull();
        assertThat(retried.getRetryCount()).isEqualTo(3);
        assertThat(retried.getStartedAt()).isNull();
        assertThat(retried.getCompletedAt()).isNull();
        assertThat(retried.getUpdatedAt()).isNotNull();
        verify(teamTaskOutboxWriter).enqueue(retried);
    }

    @Test
    void retryTask_shouldResetOnlyOfficeCallbackWhenTaskIsOnlyOfficeSave() {
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(7L)
                .teamId(10L)
                .taskType("ONLYOFFICE_SAVE")
                .status(TeamTaskEntity.STATUS_FAILED)
                .retryCount(0)
                .build();
        when(teamTaskRepository.findById(7L)).thenReturn(Optional.of(task));
        when(teamTaskRepository.save(any(TeamTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        when(teamService.requireRead(2L, 10L)).thenReturn(manager());
        teamTaskService.retryTask(2L, 10L, 7L);

        verify(onlyOfficeCallbackRepository).resetFailedByTaskId(eq(7L), any());
        verify(onlyOfficeSaveMetrics).recordRetry();
    }

    @Test
    void retryTask_shouldRejectNonFailedTask() {
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(7L)
                .teamId(10L)
                .status(TeamTaskEntity.STATUS_RUNNING)
                .build();
        when(teamTaskRepository.findById(7L)).thenReturn(Optional.of(task));

        when(teamService.requireRead(2L, 10L)).thenReturn(manager());
        assertThatThrownBy(() -> teamTaskService.retryTask(2L, 10L, 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(400));
    }

    @Test
    void retryTask_shouldHideTasksFromOtherTeams() {
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(7L)
                .teamId(11L)
                .status(TeamTaskEntity.STATUS_FAILED)
                .build();
        when(teamTaskRepository.findById(7L)).thenReturn(Optional.of(task));

        when(teamService.requireRead(2L, 10L)).thenReturn(manager());
        assertThatThrownBy(() -> teamTaskService.retryTask(2L, 10L, 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(404));
    }

    @Test
    void clearResult_shouldRemoveStoredResultMetadata() {
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(7L)
                .resultPath("audit-exports/7.csv")
                .resultFileName("team-10-audit.csv")
                .resultContentType("text/csv")
                .resultSizeBytes(12L)
                .resultExpiresAt(LocalDateTime.now())
                .resultMessageKey("task.succeeded")
                .build();
        when(teamTaskRepository.findById(7L)).thenReturn(Optional.of(task));

        teamTaskService.clearResult(7L, "结果已清理");

        assertThat(task.getResultPath()).isNull();
        assertThat(task.getResultFileName()).isNull();
        assertThat(task.getResultContentType()).isNull();
        assertThat(task.getResultSizeBytes()).isNull();
        assertThat(task.getResultExpiresAt()).isNull();
        assertThat(task.getResultMessageKey()).isEqualTo("task.resultExpired");
        verify(teamTaskRepository).save(task);
    }

    @Test
    void listExpiredResults_shouldDelegateToRepository() {
        LocalDateTime now = LocalDateTime.now();
        TeamTaskEntity task = TeamTaskEntity.builder().id(7L).build();
        when(teamTaskRepository.findByResultExpiresAtLessThanEqualAndResultPathIsNotNull(now))
                .thenReturn(List.of(task));

        assertThat(teamTaskService.listExpiredResults(now)).containsExactly(task);
    }

    private TeamMemberEntity manager() {
        return member(TeamService.ROLE_ADMIN);
    }

    private TeamMemberEntity member(String role) {
        return TeamMemberEntity.builder().teamId(10L).userId(2L).role(role).status(1).build();
    }

    private TeamTaskEntity memberTask(Long id, Long actorUserId, String taskType) {
        return TeamTaskEntity.builder()
                .id(id)
                .teamId(10L)
                .actorUserId(actorUserId)
                .taskType(taskType)
                .targetType("FILE")
                .targetId(id)
                .status(TeamTaskEntity.STATUS_FAILED)
                .retryCount(0)
                .build();
    }
}
