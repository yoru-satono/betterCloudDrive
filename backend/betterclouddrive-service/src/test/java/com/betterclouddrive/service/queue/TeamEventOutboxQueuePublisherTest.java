package com.betterclouddrive.service.queue;

import com.betterclouddrive.dal.entity.TeamEventOutboxEntity;
import com.betterclouddrive.dal.repository.TeamEventOutboxRepository;
import com.betterclouddrive.service.config.QueueProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamEventOutboxQueuePublisherTest {

    @Mock
    private TeamEventOutboxRepository teamEventOutboxRepository;

    @Mock
    private RedisStreamQueueService redisStreamQueueService;

    private QueueProperties queueProperties;
    private TeamEventOutboxQueuePublisher publisher;

    @BeforeEach
    void setUp() {
        queueProperties = new QueueProperties();
        publisher = new TeamEventOutboxQueuePublisher(teamEventOutboxRepository, redisStreamQueueService, queueProperties);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishPending_shouldPublishTeamEventsAndMarkProcessed() {
        TeamEventOutboxEntity event = event("TEAM", "TEAM_CREATED");
        when(teamEventOutboxRepository.findByStatusAndAvailableAtLessThanEqualOrderByIdAsc(
                eq(TeamEventOutboxEntity.STATUS_PENDING),
                any(LocalDateTime.class),
                any(Pageable.class)))
                .thenReturn(List.of(event));
        when(redisStreamQueueService.publish(eq("bcd:stream:team-events"), any(Map.class))).thenReturn("1-0");

        int published = publisher.publishPending(10);

        assertThat(published).isEqualTo(1);
        InOrder inOrder = inOrder(teamEventOutboxRepository, redisStreamQueueService);
        inOrder.verify(teamEventOutboxRepository).save(event);
        inOrder.verify(redisStreamQueueService).publish(eq("bcd:stream:team-events"), any(Map.class));
        inOrder.verify(teamEventOutboxRepository).save(event);
        ArgumentCaptor<Map<String, String>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(redisStreamQueueService).publish(eq("bcd:stream:team-events"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("outboxId", "99")
                .containsEntry("aggregateType", "TEAM")
                .containsEntry("eventType", "TEAM_CREATED")
                .containsEntry("targetId", "42");
        verify(teamEventOutboxRepository, times(2)).save(event);
        assertThat(event.getStatus()).isEqualTo(TeamEventOutboxEntity.STATUS_PROCESSED);
        assertThat(event.getLockedAt()).isNotNull();
        assertThat(event.getProcessedAt()).isNotNull();
        assertThat(event.getErrorMessage()).isNull();
        verify(teamEventOutboxRepository).resetStaleProcessing(
                eq(TeamEventOutboxEntity.STATUS_PROCESSING),
                eq(TeamEventOutboxEntity.STATUS_PENDING),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq("Processing lock expired before dispatch"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishPending_shouldRouteNotificationAggregateToNotificationStream() {
        TeamEventOutboxEntity event = event("NOTIFICATION", "TEAM_INVITE");
        when(teamEventOutboxRepository.findByStatusAndAvailableAtLessThanEqualOrderByIdAsc(
                eq(TeamEventOutboxEntity.STATUS_PENDING),
                any(LocalDateTime.class),
                any(Pageable.class)))
                .thenReturn(List.of(event));
        when(redisStreamQueueService.publish(eq("bcd:stream:notifications"), any(Map.class))).thenReturn("1-1");

        int published = publisher.publishPending(10);

        assertThat(published).isEqualTo(1);
        verify(redisStreamQueueService).publish(eq("bcd:stream:notifications"), any(Map.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishPending_shouldMarkFailedAfterMaxDeliveries() {
        TeamEventOutboxEntity event = event("TEAM", "TEAM_DELETED");
        event.setRetryCount(4);
        when(teamEventOutboxRepository.findByStatusAndAvailableAtLessThanEqualOrderByIdAsc(
                eq(TeamEventOutboxEntity.STATUS_PENDING),
                any(LocalDateTime.class),
                any(Pageable.class)))
                .thenReturn(List.of(event));
        when(redisStreamQueueService.publish(eq("bcd:stream:team-events"), any(Map.class)))
                .thenThrow(new IllegalStateException("redis down"));

        int published = publisher.publishPending(10);

        assertThat(published).isZero();
        ArgumentCaptor<TeamEventOutboxEntity> eventCaptor = ArgumentCaptor.forClass(TeamEventOutboxEntity.class);
        verify(teamEventOutboxRepository, times(2)).save(eventCaptor.capture());
        TeamEventOutboxEntity failed = eventCaptor.getAllValues().getLast();
        assertThat(failed.getStatus()).isEqualTo(TeamEventOutboxEntity.STATUS_FAILED);
        assertThat(failed.getRetryCount()).isEqualTo(5);
        assertThat(failed.getErrorMessage()).isEqualTo("redis down");
        assertThat(failed.getProcessedAt()).isNotNull();
    }

    @Test
    void publishPending_shouldResetStaleProcessingBeforePublishingPendingEvents() {
        when(teamEventOutboxRepository.findByStatusAndAvailableAtLessThanEqualOrderByIdAsc(
                eq(TeamEventOutboxEntity.STATUS_PENDING),
                any(LocalDateTime.class),
                any(Pageable.class)))
                .thenReturn(List.of());

        int published = publisher.publishPending(10);

        assertThat(published).isZero();
        verify(teamEventOutboxRepository).resetStaleProcessing(
                eq(TeamEventOutboxEntity.STATUS_PROCESSING),
                eq(TeamEventOutboxEntity.STATUS_PENDING),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq("Processing lock expired before dispatch"));
    }

    private TeamEventOutboxEntity event(String aggregateType, String eventType) {
        LocalDateTime now = LocalDateTime.now();
        return TeamEventOutboxEntity.builder()
                .id(99L)
                .aggregateType(aggregateType)
                .aggregateId(10L)
                .eventType(eventType)
                .actorUserId(7L)
                .targetType("TEAM")
                .targetId(42L)
                .payload("payload")
                .status(TeamEventOutboxEntity.STATUS_PENDING)
                .retryCount(0)
                .availableAt(now.minusSeconds(1))
                .createdAt(now.minusMinutes(1))
                .updatedAt(now.minusMinutes(1))
                .build();
    }
}
