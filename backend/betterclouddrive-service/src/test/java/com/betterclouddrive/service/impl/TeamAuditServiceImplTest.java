package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.entity.TeamMemberEntity;
import com.betterclouddrive.dal.entity.TeamOperationLogEntity;
import com.betterclouddrive.dal.repository.TeamMemberRepository;
import com.betterclouddrive.dal.repository.TeamOperationLogRepository;
import com.betterclouddrive.dal.repository.TeamRepository;
import com.betterclouddrive.service.TeamEventOutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class TeamAuditServiceImplTest {

    @Mock private TeamOperationLogRepository teamOperationLogRepository;
    @Mock private TeamMemberRepository teamMemberRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private TeamEventOutboxService teamEventOutboxService;
    @InjectMocks private TeamAuditServiceImpl teamAuditService;

    @Test
    void record_shouldNormalizeActionAndTargetType() {
        when(teamOperationLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        teamAuditService.record(10L, 2L, " share_create ", " share ", 9L, "details");

        ArgumentCaptor<TeamOperationLogEntity> captor = ArgumentCaptor.forClass(TeamOperationLogEntity.class);
        verify(teamOperationLogRepository).save(captor.capture());
        assertThat(captor.getValue().getTeamId()).isEqualTo(10L);
        assertThat(captor.getValue().getActorUserId()).isEqualTo(2L);
        assertThat(captor.getValue().getAction()).isEqualTo("SHARE_CREATE");
        assertThat(captor.getValue().getTargetType()).isEqualTo("SHARE");
        assertThat(captor.getValue().getResult()).isEqualTo(1);
        assertThat(captor.getValue().getStatusCode()).isEqualTo(200);
        verify(teamEventOutboxService).recordTeamEvent(10L, 2L, "SHARE_CREATE", "SHARE", 9L, "details");
    }

    @Test
    void recordFailure_shouldPersistFailedResult() {
        when(teamOperationLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        teamAuditService.recordFailure(10L, 2L, "office_save_failed", "file", 9L, 500003, "download failed");

        ArgumentCaptor<TeamOperationLogEntity> captor = ArgumentCaptor.forClass(TeamOperationLogEntity.class);
        verify(teamOperationLogRepository).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("OFFICE_SAVE_FAILED");
        assertThat(captor.getValue().getTargetType()).isEqualTo("FILE");
        assertThat(captor.getValue().getResult()).isZero();
        assertThat(captor.getValue().getStatusCode()).isEqualTo(500003);
        assertThat(captor.getValue().getDetails()).isEqualTo("download failed");
        verify(teamEventOutboxService).recordTeamEvent(10L, 2L, "OFFICE_SAVE_FAILED", "FILE", 9L, "download failed");
    }

    @Test
    void listLogs_shouldRejectDeletedTeamBeforeMemberCheck() {
        when(teamRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> teamAuditService.listLogs(2L, 10L, null, null, null, null, null, null, 1, 20))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(404));

        verifyNoInteractions(teamMemberRepository);
        verify(teamOperationLogRepository, never()).findAll(any(Specification.class), any(PageRequest.class));
    }

    @Test
    void listLogs_shouldAllowOwnerOrAdmin() {
        TeamEntity team = TeamEntity.builder().id(10L).build();
        TeamMemberEntity member = TeamMemberEntity.builder().teamId(10L).userId(2L).role("ADMIN").status(1).build();
        TeamOperationLogEntity log = TeamOperationLogEntity.builder()
                .id(1L).teamId(10L).actorUserId(2L).action("SHARE_CREATE").build();
        when(teamRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(team));
        when(teamMemberRepository.findByTeamIdAndUserIdAndStatus(10L, 2L, 1)).thenReturn(Optional.of(member));
        when(teamOperationLogRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(log), PageRequest.of(0, 20), 1));

        PageResult<TeamOperationLogEntity> result =
                teamAuditService.listLogs(2L, 10L, null, null, null, null, null, null, 1, 20);

        assertThat(result.getRecords()).containsExactly(log);
        assertThat(result.getTotal()).isEqualTo(1);
    }

    @Test
    void exportLogs_shouldRejectWhenResultExceedsLimit() {
        TeamEntity team = TeamEntity.builder().id(10L).build();
        TeamMemberEntity member = TeamMemberEntity.builder().teamId(10L).userId(2L).role("ADMIN").status(1).build();
        when(teamRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(team));
        when(teamMemberRepository.findByTeamIdAndUserIdAndStatus(10L, 2L, 1)).thenReturn(Optional.of(member));
        when(teamOperationLogRepository.count(any(Specification.class))).thenReturn(3L);

        assertThatThrownBy(() -> teamAuditService.exportLogs(2L, 10L, null, null, null, null, null, null, 2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Audit export exceeds 2 rows");

        verify(teamOperationLogRepository, never()).findAll(any(Specification.class), any(PageRequest.class));
    }
}
