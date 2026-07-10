package com.betterclouddrive.service.queue;

import com.betterclouddrive.dal.entity.TeamEventOutboxEntity;
import com.betterclouddrive.dal.repository.TeamEventOutboxRepository;
import com.betterclouddrive.service.NotificationService;
import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.metrics.QueueMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamNotificationDispatchServiceImplTest {

    @Mock
    private TeamNotificationOutboxWriter teamNotificationOutboxWriter;

    @Mock
    private TeamEventOutboxRepository teamEventOutboxRepository;

    @Mock
    private RedisStreamQueueService redisStreamQueueService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private QueueMetrics queueMetrics;

    private QueueProperties queueProperties;
    private ObjectMapper objectMapper;
    private TeamNotificationDispatchServiceImpl service;

    @BeforeEach
    void setUp() {
        queueProperties = new QueueProperties();
        objectMapper = new ObjectMapper();
        service = new TeamNotificationDispatchServiceImpl(
                teamNotificationOutboxWriter,
                teamEventOutboxRepository,
                redisStreamQueueService,
                notificationService,
                queueProperties,
                objectMapper,
                queueMetrics);
    }

    @Test
    void enqueueUserNotification_shouldWriteOutboxWhenQueueEnabled() {
        service.enqueueUserNotification(2L, 10L, "TEAM_INVITE", "团队邀请", "加入团队", "/teams/10", true);

        verify(teamNotificationOutboxWriter).enqueueUser(2L, 10L, "TEAM_INVITE", "团队邀请", "加入团队", "/teams/10", true);
        verify(notificationService, never()).notifyUser(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void enqueueTeamMembersNotification_shouldFallbackWhenQueueDisabled() {
        queueProperties.setEnabled(false);

        service.enqueueTeamMembersNotification(10L, 2L, "TEAM_DELETE", "团队已删除", "团队已被删除", null, true);

        verify(notificationService).notifyTeamMembers(10L, 2L, "TEAM_DELETE", "团队已删除", "团队已被删除", null, true);
        verify(teamNotificationOutboxWriter, never()).enqueueTeamMembers(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void processQueuedNotifications_shouldDispatchUserNotificationAndMarkProcessed() throws Exception {
        TeamEventOutboxEntity event = processingEvent(99L, 0);
        QueueMessage message = userMessage(event.getId());
        when(redisStreamQueueService.readGroup(
                eq("bcd:stream:notifications"),
                eq("bcd-notification-workers"),
                eq("backend-worker"),
                eq(10),
                any(Duration.class)))
                .thenReturn(List.of(message));
        when(teamEventOutboxRepository.findById(99L)).thenReturn(Optional.of(event));

        int processed = service.processQueuedNotifications(10);

        assertThat(processed).isEqualTo(1);
        verify(notificationService).notifyUser(2L, 10L, "TEAM_INVITE", "团队邀请", "加入团队", "/teams/10", false);
        ArgumentCaptor<TeamEventOutboxEntity> eventCaptor = ArgumentCaptor.forClass(TeamEventOutboxEntity.class);
        verify(teamEventOutboxRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo(TeamEventOutboxEntity.STATUS_PROCESSED);
        assertThat(eventCaptor.getValue().getProcessedAt()).isNotNull();
        assertThat(eventCaptor.getValue().getErrorMessage()).isNull();
        verify(redisStreamQueueService).acknowledge("bcd:stream:notifications", "bcd-notification-workers", "1-0");
    }

    @Test
    void processQueuedNotifications_shouldRetryFailedDispatchByReturningOutboxToPending() throws Exception {
        TeamEventOutboxEntity event = processingEvent(99L, 0);
        QueueMessage message = userMessage(event.getId());
        when(redisStreamQueueService.readGroup(any(), any(), any(), anyInt(), any(Duration.class)))
                .thenReturn(List.of(message));
        when(teamEventOutboxRepository.findById(99L)).thenReturn(Optional.of(event));
        doThrow(new IllegalStateException("notification db down"))
                .when(notificationService).notifyUser(2L, 10L, "TEAM_INVITE", "团队邀请", "加入团队", "/teams/10", false);

        int processed = service.processQueuedNotifications(10);

        assertThat(processed).isZero();
        ArgumentCaptor<TeamEventOutboxEntity> eventCaptor = ArgumentCaptor.forClass(TeamEventOutboxEntity.class);
        verify(teamEventOutboxRepository).save(eventCaptor.capture());
        TeamEventOutboxEntity saved = eventCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(TeamEventOutboxEntity.STATUS_PENDING);
        assertThat(saved.getRetryCount()).isEqualTo(1);
        assertThat(saved.getAvailableAt()).isAfter(LocalDateTime.now().minusSeconds(1));
        assertThat(saved.getErrorMessage()).isEqualTo("notification db down");
        verify(redisStreamQueueService, never()).moveToDeadLetter(any(), any());
        verify(redisStreamQueueService).acknowledge("bcd:stream:notifications", "bcd-notification-workers", "1-0");
    }

    @Test
    void processQueuedNotifications_shouldMoveFailedDispatchToDeadLetterAtMaxDeliveries() throws Exception {
        TeamEventOutboxEntity event = processingEvent(99L, 4);
        QueueMessage message = userMessage(event.getId());
        when(redisStreamQueueService.readGroup(any(), any(), any(), anyInt(), any(Duration.class)))
                .thenReturn(List.of(message));
        when(teamEventOutboxRepository.findById(99L)).thenReturn(Optional.of(event));
        doThrow(new IllegalStateException("notification db down"))
                .when(notificationService).notifyUser(2L, 10L, "TEAM_INVITE", "团队邀请", "加入团队", "/teams/10", false);

        int processed = service.processQueuedNotifications(10);

        assertThat(processed).isZero();
        ArgumentCaptor<TeamEventOutboxEntity> eventCaptor = ArgumentCaptor.forClass(TeamEventOutboxEntity.class);
        verify(teamEventOutboxRepository).save(eventCaptor.capture());
        TeamEventOutboxEntity saved = eventCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(TeamEventOutboxEntity.STATUS_FAILED);
        assertThat(saved.getRetryCount()).isEqualTo(5);
        assertThat(saved.getProcessedAt()).isNotNull();
        verify(redisStreamQueueService).moveToDeadLetter(message, "notification db down");
        verify(queueMetrics).recordDeadLetter("bcd:stream:notifications");
        verify(redisStreamQueueService).acknowledge("bcd:stream:notifications", "bcd-notification-workers", "1-0");
    }

    @Test
    void processQueuedNotifications_shouldDeadLetterMessageWithoutOutboxEvent() throws Exception {
        QueueMessage message = userMessage(99L);
        when(redisStreamQueueService.readGroup(any(), any(), any(), anyInt(), any(Duration.class)))
                .thenReturn(List.of(message));
        when(teamEventOutboxRepository.findById(99L)).thenReturn(Optional.empty());

        int processed = service.processQueuedNotifications(10);

        assertThat(processed).isZero();
        verify(notificationService, never()).notifyUser(any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(teamEventOutboxRepository, never()).save(any());
        verify(redisStreamQueueService).moveToDeadLetter(message, "Notification outbox event not found");
        verify(queueMetrics).recordDeadLetter("bcd:stream:notifications");
        verify(redisStreamQueueService).acknowledge("bcd:stream:notifications", "bcd-notification-workers", "1-0");
    }

    @Test
    void processQueuedNotifications_shouldAcknowledgeAlreadyHandledOutboxWithoutDispatchingAgain() throws Exception {
        TeamEventOutboxEntity event = processingEvent(99L, 0);
        event.setStatus(TeamEventOutboxEntity.STATUS_PROCESSED);
        QueueMessage message = userMessage(event.getId());
        when(redisStreamQueueService.readGroup(any(), any(), any(), anyInt(), any(Duration.class)))
                .thenReturn(List.of(message));
        when(teamEventOutboxRepository.findById(99L)).thenReturn(Optional.of(event));

        int processed = service.processQueuedNotifications(10);

        assertThat(processed).isEqualTo(1);
        verify(notificationService, never()).notifyUser(any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(teamEventOutboxRepository, never()).save(any());
        verify(redisStreamQueueService).acknowledge("bcd:stream:notifications", "bcd-notification-workers", "1-0");
    }

    @Test
    void processQueuedNotifications_shouldIgnoreQueueWhenDisabled() {
        queueProperties.setEnabled(false);

        int processed = service.processQueuedNotifications(10);

        assertThat(processed).isZero();
        verify(redisStreamQueueService, never()).readGroup(any(), any(), any(), anyInt(), any());
    }

    private QueueMessage userMessage(Long outboxId) throws Exception {
        NotificationDispatchPayload payload = new NotificationDispatchPayload(
                2L,
                10L,
                null,
                "TEAM_INVITE",
                "团队邀请",
                "加入团队",
                "/teams/10",
                false);
        return new QueueMessage("bcd:stream:notifications", "1-0", Map.of(
                "outboxId", String.valueOf(outboxId),
                "targetType", TeamNotificationOutboxWriter.TARGET_USER,
                "payload", objectMapper.writeValueAsString(payload)));
    }

    private TeamEventOutboxEntity processingEvent(Long id, int retryCount) {
        LocalDateTime now = LocalDateTime.now();
        return TeamEventOutboxEntity.builder()
                .id(id)
                .aggregateType(TeamNotificationOutboxWriter.AGGREGATE_NOTIFICATION)
                .aggregateId(10L)
                .eventType("TEAM_INVITE")
                .targetType(TeamNotificationOutboxWriter.TARGET_USER)
                .targetId(2L)
                .status(TeamEventOutboxEntity.STATUS_PROCESSING)
                .retryCount(retryCount)
                .lockedAt(now.minusSeconds(1))
                .createdAt(now.minusMinutes(1))
                .updatedAt(now.minusSeconds(1))
                .build();
    }
}
