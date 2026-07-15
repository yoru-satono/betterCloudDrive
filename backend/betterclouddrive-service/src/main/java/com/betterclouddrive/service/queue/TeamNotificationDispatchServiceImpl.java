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
import java.util.Map;
import com.betterclouddrive.common.message.MessageKeys;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class TeamNotificationDispatchServiceImpl implements TeamNotificationDispatchService {

    private final TeamNotificationOutboxWriter teamNotificationOutboxWriter;
    private final TeamEventOutboxRepository teamEventOutboxRepository;
    private final RabbitQueueService rabbitQueueService;
    private final NotificationService notificationService;
    private final QueueProperties queueProperties;
    private final ObjectMapper objectMapper;
    private final QueueMetrics queueMetrics;

    @Override
    public void enqueueUserNotification(Long userId, Long teamId, String type, String title, String content, String link, boolean email) {
        enqueueUserNotification(userId, teamId, type, Map.of(), link, email);
    }

    @Override
    public void enqueueUserNotification(Long userId, Long teamId, String type, Map<String, Object> messageParams, String link, boolean email) {
        if (!queueProperties.isEnabled()) {
            notificationService.notifyUser(userId, teamId, type, MessageKeys.notificationTitle(type),
                    MessageKeys.notificationContent(type), messageParams, link, email);
            return;
        }
        teamNotificationOutboxWriter.enqueueUser(userId, teamId, type, messageParams, link, email);
    }

    @Override
    public void enqueueTeamMembersNotification(Long teamId, Long excludeUserId, String type, String title, String content, String link, boolean email) {
        if (!queueProperties.isEnabled()) {
            notificationService.notifyTeamMembers(teamId, excludeUserId, type, title, content, link, email);
            return;
        }
        teamNotificationOutboxWriter.enqueueTeamMembers(teamId, excludeUserId, type, title, content, link, email);
    }

    @RabbitListener(queues = "${queue.rabbitmq.notifications-queue:bcd.notifications}", autoStartup = "${queue.enabled:true}")
    public void processQueuedNotification(Message raw, Channel channel) throws IOException {
        queueMetrics.beginConsumer("notifications");
        long started = queueMetrics.startProcessing();
        try {
            long tag = raw.getMessageProperties().getDeliveryTag();
            QueueMessage message;
            try {
                message = new QueueMessage("notifications", raw.getMessageProperties().getMessageId(), rabbitQueueService.read(raw));
            } catch (RuntimeException e) {
                queueMetrics.recordDeadLetter("notifications");
                channel.basicReject(tag, false);
                queueMetrics.recordProcessing("notification-dispatch", "failure", started);
                return;
            }
            boolean accepted = processMessage(message);
            if (accepted) channel.basicAck(tag, false); else channel.basicReject(tag, false);
            queueMetrics.recordProcessing("notification-dispatch", accepted ? "success" : "failure", started);
        } finally {
            queueMetrics.endConsumer("notifications");
        }
    }

    private boolean processMessage(QueueMessage message) {
        Long outboxId = parseLong(message.body().get("outboxId"));
        TeamEventOutboxEntity event = outboxId == null
                ? null
                : teamEventOutboxRepository.findById(outboxId).orElse(null);
        if (event == null) {
            queueMetrics.recordDeadLetter("notifications");
            return false;
        }
        if (!TeamEventOutboxEntity.STATUS_PROCESSING.equals(event.getStatus())) {
            return true;
        }
        try {
            dispatchIfNeeded(message);
            markProcessed(event);
            return true;
        } catch (RuntimeException e) {
            return handleFailure(event, message, e);
        }
    }

    private void dispatchIfNeeded(QueueMessage message) {
        String targetType = message.body().get("targetType");
        if (!TeamNotificationOutboxWriter.TARGET_USER.equals(targetType)
                && !TeamNotificationOutboxWriter.TARGET_TEAM_MEMBERS.equals(targetType)) {
            throw new IllegalStateException("Unsupported notification target type: " + targetType);
        }
        NotificationDispatchPayload payload = readPayload(message.body().get("payload"));
        if (TeamNotificationOutboxWriter.TARGET_USER.equals(targetType)) {
            notificationService.notifyUser(
                    payload.userId(),
                    payload.teamId(),
                    payload.type(),
                    payload.titleKey(),
                    payload.contentKey(),
                    payload.messageParams(),
                    payload.link(),
                    payload.email());
            return;
        }
        notificationService.notifyTeamMembers(
                payload.teamId(),
                payload.excludeUserId(),
                payload.type(),
                payload.titleKey(),
                payload.contentKey(),
                payload.messageParams(),
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

    private boolean handleFailure(TeamEventOutboxEntity event, QueueMessage message, RuntimeException error) {
        if (event == null) {
            queueMetrics.recordDeadLetter("notifications");
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int retryCount = event.getRetryCount() == null ? 1 : event.getRetryCount() + 1;
        event.setRetryCount(retryCount);
        event.setErrorMessage(StringUtils.hasText(error.getMessage()) ? error.getMessage() : error.getClass().getSimpleName());
        event.setUpdatedAt(now);
        if (retryCount >= queueProperties.getRabbitmq().getMaxDeliveries()) {
            event.setStatus(TeamEventOutboxEntity.STATUS_FAILED);
            event.setProcessedAt(now);
            teamEventOutboxRepository.save(event);
            queueMetrics.recordDeadLetter("notifications");
            return false;
        }
        event.setStatus(TeamEventOutboxEntity.STATUS_PENDING);
        event.setAvailableAt(now.plusSeconds(Math.min(60, retryCount * 5L)));
        teamEventOutboxRepository.save(event);
        return true;
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
