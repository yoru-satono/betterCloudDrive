package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.betterclouddrive.dal.entity.TeamEventOutboxEntity;
import com.betterclouddrive.dal.repository.TeamEventOutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TeamEventOutboxServiceImplTest {

    @Mock private TeamEventOutboxRepository teamEventOutboxRepository;
    @Spy private com.betterclouddrive.service.observability.AsyncTraceService asyncTraceService =
            new com.betterclouddrive.service.observability.AsyncTraceService(
                    io.opentelemetry.api.OpenTelemetry.noop());
    @InjectMocks private TeamEventOutboxServiceImpl service;

    @Test
    void recordTeamEvent_shouldPersistPendingNormalizedEvent() {
        service.recordTeamEvent(10L, 2L, " invite_accept ", " invite ", 5L, "accepted");

        ArgumentCaptor<TeamEventOutboxEntity> captor = ArgumentCaptor.forClass(TeamEventOutboxEntity.class);
        verify(teamEventOutboxRepository).save(captor.capture());
        TeamEventOutboxEntity event = captor.getValue();
        assertThat(event.getAggregateType()).isEqualTo("TEAM");
        assertThat(event.getAggregateId()).isEqualTo(10L);
        assertThat(event.getEventType()).isEqualTo("INVITE_ACCEPT");
        assertThat(event.getActorUserId()).isEqualTo(2L);
        assertThat(event.getTargetType()).isEqualTo("INVITE");
        assertThat(event.getTargetId()).isEqualTo(5L);
        assertThat(event.getPayload()).isEqualTo("accepted");
        assertThat(event.getStatus()).isEqualTo(TeamEventOutboxEntity.STATUS_PENDING);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getAvailableAt()).isNotNull();
        assertThat(event.getCreatedAt()).isNotNull();
    }

    @Test
    void recordNotificationEvent_shouldPersistNotificationAggregate() {
        service.recordNotificationEvent(99L, 2L, 10L, "team_quota_alert", "quota high");

        ArgumentCaptor<TeamEventOutboxEntity> captor = ArgumentCaptor.forClass(TeamEventOutboxEntity.class);
        verify(teamEventOutboxRepository).save(captor.capture());
        TeamEventOutboxEntity event = captor.getValue();
        assertThat(event.getAggregateType()).isEqualTo("NOTIFICATION");
        assertThat(event.getAggregateId()).isEqualTo(99L);
        assertThat(event.getActorUserId()).isEqualTo(2L);
        assertThat(event.getEventType()).isEqualTo("TEAM_QUOTA_ALERT");
        assertThat(event.getTargetType()).isEqualTo("NOTIFICATION");
        assertThat(event.getTargetId()).isEqualTo(99L);
        assertThat(event.getPayload()).contains("userId=2", "teamId=10", "quota high");
    }

    @Test
    void recordTeamEvent_shouldIgnoreBlankEventType() {
        service.recordTeamEvent(10L, 2L, " ", "TEAM", 10L, null);

        verify(teamEventOutboxRepository, never()).save(any());
    }
}
