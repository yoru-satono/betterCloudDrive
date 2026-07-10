package com.betterclouddrive.service.queue;

import com.betterclouddrive.dal.entity.TeamEventOutboxEntity;
import com.betterclouddrive.dal.repository.TeamEventOutboxRepository;
import com.betterclouddrive.service.NotificationService;
import com.betterclouddrive.service.TeamNotificationDispatchService;
import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.metrics.QueueMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TeamNotificationDispatchServiceImpl implements TeamNotificationDispatchService {

    private final TeamNotificationOutboxWriter teamNotificationOutboxWriter;
    private final TeamEventOutboxRepository teamEventOutboxRepository;
    private final RedisStreamQueueService redisStreamQueueService;
    private final NotificationService notificationService;
    private final QueueProperties queueProperties;
    private final ObjectMapper objectMapper;
    private final QueueMetrics queueMetrics;

    @Override
    public void enqueueUserNotification(Long userId, Long teamId, String type, String title, String content, String link, boolean email) {
        if (!queueProperties.isEnabled()) {
            notificationService.notifyUser(userId, teamId, type, title, content, link, email);
            return;
        }
        teamNotificationOutboxWriter.enqueueUser(userId, teamId, type, title, content, link, email);
    }

    @Override
    public void enqueueTeamMembersNotification(Long teamId, Long excludeUserId, String type, String title, String content, String link, boolean email) {
        if (!queueProperties.isEnabled()) {
            notificationService.notifyTeamMembers(teamId, excludeUserId, type, title, content, link, email);
            return;
        }
        teamNotificationOutboxWriter.enqueueTeamMembers(teamId, excludeUserId, type, title, content, link, email);
    }

    @Override
    public int processQueuedNotifications(int batchSize) {
        if (!queueProperties.isEnabled()) {
            return 0;
        }
        QueueProperties.Redis redis = queueProperties.getRedis();
        List<QueueMessage> messages = redisStreamQueueService.readGroup(
                redis.getStreams().getNotifications(),
                redis.getGroups().getNotifications(),
                redis.getConsumerName(),
                batchSize > 0 ? batchSize : redis.getReadBatchSize(),
                Duration.ofMillis(redis.getBlockTimeoutMs()));
        int processed = 0;
        for (QueueMessage message : messages) {
            if (processMessage(message)) {
                processed++;
            }
        }
        return processed;
    }

    private boolean processMessage(QueueMessage message) {
        Long outboxId = parseLong(message.body().get("outboxId"));
        TeamEventOutboxEntity event = outboxId == null
                ? null
                : teamEventOutboxRepository.findById(outboxId).orElse(null);
        if (event == null) {
            moveToDeadLetter(message, "Notification outbox event not found");
            acknowledge(message);
            return false;
        }
        if (!TeamEventOutboxEntity.STATUS_PROCESSING.equals(event.getStatus())) {
            acknowledge(message);
            return true;
        }
        try {
            dispatchIfNeeded(message);
            markProcessed(event);
            acknowledge(message);
            return true;
        } catch (RuntimeException e) {
            handleFailure(event, message, e);
            return false;
        }
    }

    private void dispatchIfNeeded(QueueMessage message) {
        String targetType = message.body().get("targetType");
        if (!TeamNotificationOutboxWriter.TARGET_USER.equals(targetType)
                && !TeamNotificationOutboxWriter.TARGET_TEAM_MEMBERS.equals(targetType)) {
            return;
        }
        NotificationDispatchPayload payload = readPayload(message.body().get("payload"));
        if (TeamNotificationOutboxWriter.TARGET_USER.equals(targetType)) {
            notificationService.notifyUser(
                    payload.userId(),
                    payload.teamId(),
                    payload.type(),
                    payload.title(),
                    payload.content(),
                    payload.link(),
                    payload.email());
            return;
        }
        notificationService.notifyTeamMembers(
                payload.teamId(),
                payload.excludeUserId(),
                payload.type(),
                payload.title(),
                payload.content(),
                payload.link(),
                payload.email());
    }

    private NotificationDispatchPayload readPayload(String payload) {
        if (!StringUtils.hasText(payload)) {
            throw new IllegalStateException("Notification dispatch payload is empty");
        }
        try {
            return objectMapper.readValue(payload, NotificationDispatchPayload.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Notification dispatch payload is invalid", e);
        }
    }

    private void markProcessed(TeamEventOutboxEntity event) {
        if (event == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        event.setStatus(TeamEventOutboxEntity.STATUS_PROCESSED);
        event.setProcessedAt(now);
        event.setErrorMessage(null);
        event.setUpdatedAt(now);
        teamEventOutboxRepository.save(event);
    }

    private void handleFailure(TeamEventOutboxEntity event, QueueMessage message, RuntimeException error) {
        if (event == null) {
            moveToDeadLetter(message, error.getMessage());
            acknowledge(message);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int retryCount = event.getRetryCount() == null ? 1 : event.getRetryCount() + 1;
        event.setRetryCount(retryCount);
        event.setErrorMessage(StringUtils.hasText(error.getMessage()) ? error.getMessage() : error.getClass().getSimpleName());
        event.setUpdatedAt(now);
        if (retryCount >= queueProperties.getRedis().getMaxDeliveries()) {
            event.setStatus(TeamEventOutboxEntity.STATUS_FAILED);
            event.setProcessedAt(now);
            teamEventOutboxRepository.save(event);
            moveToDeadLetter(message, event.getErrorMessage());
            acknowledge(message);
            return;
        }
        event.setStatus(TeamEventOutboxEntity.STATUS_PENDING);
        event.setAvailableAt(now.plusSeconds(Math.min(60, retryCount * 5L)));
        teamEventOutboxRepository.save(event);
        acknowledge(message);
    }

    private void acknowledge(QueueMessage message) {
        QueueProperties.Redis redis = queueProperties.getRedis();
        redisStreamQueueService.acknowledge(message.stream(), redis.getGroups().getNotifications(), message.id());
    }

    private void moveToDeadLetter(QueueMessage message, String reason) {
        redisStreamQueueService.moveToDeadLetter(message, reason);
        queueMetrics.recordDeadLetter(message.stream());
    }

    private Long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid outbox id in queue message: {}", value);
            return null;
        }
    }
}
