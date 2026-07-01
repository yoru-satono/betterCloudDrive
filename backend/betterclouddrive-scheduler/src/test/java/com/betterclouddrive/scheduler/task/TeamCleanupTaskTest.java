package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.repository.TeamRepository;
import com.betterclouddrive.service.TeamPurgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamCleanupTaskTest {

    @Mock private TeamRepository teamRepository;
    @Mock private TeamPurgeService teamPurgeService;
    @InjectMocks private TeamCleanupTask task;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(task, "retentionDays", 30);
    }

    @Test
    void shouldPurgeExpiredDeletedTeams() {
        TeamEntity team = TeamEntity.builder()
                .id(10L)
                .deletedAt(LocalDateTime.now().minusDays(31))
                .build();

        when(teamRepository.findExpiredDeletedTeams(any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(team), Collections.emptyList());

        task.cleanupExpiredDeletedTeams();

        verify(teamPurgeService).purgeTeam(10L);
        verify(teamRepository, times(2)).findExpiredDeletedTeams(
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(PageRequest.of(0, 100)));
    }

    @Test
    void shouldStopBatchForRetryWhenPurgeFails() {
        TeamEntity team = TeamEntity.builder()
                .id(10L)
                .deletedAt(LocalDateTime.now().minusDays(31))
                .build();

        when(teamRepository.findExpiredDeletedTeams(any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(team));
        doThrow(new RuntimeException("storage down")).when(teamPurgeService).purgeTeam(10L);

        task.cleanupExpiredDeletedTeams();

        verify(teamPurgeService).purgeTeam(10L);
        verify(teamRepository, times(1)).findExpiredDeletedTeams(
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(PageRequest.of(0, 100)));
    }
}
