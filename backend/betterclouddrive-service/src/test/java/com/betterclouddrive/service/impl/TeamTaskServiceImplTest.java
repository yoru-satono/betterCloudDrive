package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.dal.repository.TeamTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class TeamTaskServiceImplTest {

    @Mock
    private TeamTaskRepository teamTaskRepository;

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
        assertThat(task.getTitle()).isEqualTo("导出审计");
        assertThat(task.getStatus()).isEqualTo(TeamTaskEntity.STATUS_RUNNING);
        assertThat(task.getProgress()).isZero();
        assertThat(task.getRetryCount()).isZero();
        assertThat(task.getStartedAt()).isNotNull();
        assertThat(task.getCreatedAt()).isNotNull();
        assertThat(task.getUpdatedAt()).isNotNull();
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

        PageResult<TeamTaskEntity> result = teamTaskService.listTasks(10L, " failed ", " audit_export ", 2, 10);

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
    void retryTask_shouldMoveFailedTaskBackToPendingAndIncrementRetryCount() {
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(7L)
                .teamId(10L)
                .status(TeamTaskEntity.STATUS_FAILED)
                .progress(40)
                .errorMessage("download failed")
                .retryCount(2)
                .build();
        when(teamTaskRepository.findById(7L)).thenReturn(Optional.of(task));
        when(teamTaskRepository.save(any(TeamTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TeamTaskEntity retried = teamTaskService.retryTask(10L, 7L);

        assertThat(retried.getStatus()).isEqualTo(TeamTaskEntity.STATUS_PENDING);
        assertThat(retried.getProgress()).isZero();
        assertThat(retried.getErrorMessage()).isNull();
        assertThat(retried.getRetryCount()).isEqualTo(3);
        assertThat(retried.getStartedAt()).isNull();
        assertThat(retried.getCompletedAt()).isNull();
        assertThat(retried.getUpdatedAt()).isNotNull();
    }

    @Test
    void retryTask_shouldRejectNonFailedTask() {
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(7L)
                .teamId(10L)
                .status(TeamTaskEntity.STATUS_RUNNING)
                .build();
        when(teamTaskRepository.findById(7L)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> teamTaskService.retryTask(10L, 7L))
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

        assertThatThrownBy(() -> teamTaskService.retryTask(10L, 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(404));
    }
}
