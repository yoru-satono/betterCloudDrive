package com.betterclouddrive.service.queue;

import com.betterclouddrive.dal.entity.TeamEventOutboxEntity;
import com.betterclouddrive.dal.repository.TeamEventOutboxRepository;
import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.observability.AsyncTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TeamEventOutboxQueuePublisher {

    private static final String AGGREGATE_NOTIFICATION = "NOTIFICATION";
    private static final Duration PROCESSING_LOCK_TIMEOUT = Duration.ofSeconds(30);

    private final TeamEventOutboxRepository teamEventOutboxRepository;
    private final RabbitQueueService rabbitQueueService;
    private final QueueProperties queueProperties;
    private final AsyncTraceService asyncTraceService;

    public int publishPending(int batchSize) {
        if (!queueProperties.isEnabled()) {
            return 0;
        }
        resetStaleProcessingEvents();
        int limit = batchSize > 0 ? batchSize : queueProperties.getRabbitmq().getOutboxPublishBatchSize();
        List<TeamEventOutboxEntity> events = teamEventOutboxRepository.findReadyByDispatchPriority(
                TeamEventOutboxEntity.STATUS_PENDING,
                LocalDateTime.now(),
                PageRequest.of(0, limit));
        int published = 0;
        for (TeamEventOutboxEntity event : events) {
            if (publishEvent(event)) {
                published++;
            }
        }
        return published;
    }

    private boolean publishEvent(TeamEventOutboxEntity event) {
        String destination = isNotification(event) ? "notifications"
                : isTeamTask(event) ? "team-tasks" : "unknown";
        String taskType = isTeamTask(event) ? event.getTargetType() : null;
        AsyncTraceService.TraceContext link =
                new AsyncTraceService.TraceContext(event.getTraceparent(), event.getTracestate());
        AsyncTraceService.TraceOperation trace =
                asyncTraceService.startLinked("outbox.publish", link, destination, taskType);
        try (trace) {
            try {
                markProcessing(event);
                teamEventOutboxRepository.save(event);
                if (!isNotification(event) && !isTeamTask(event)) {
                    markProcessed(event);
                    teamEventOutboxRepository.save(event);
                    trace.success();
                    return true;
                }
                String messageId = rabbitQueueService.publish(routingKeyFor(event), payloadFor(event));
                if (!StringUtils.hasText(messageId)) {
                    markFailedOrRetry(event, new IllegalStateException("RabbitMQ did not confirm the message"));
                    teamEventOutboxRepository.save(event);
                    recordFailureOutcome(trace, event);
                    return false;
                }
                if (!isNotification(event)) {
                    markProcessed(event);
                    teamEventOutboxRepository.save(event);
                }
                trace.success();
                return true;
            } catch (RuntimeException e) {
                markFailedOrRetry(event, e);
                teamEventOutboxRepository.save(event);
                recordFailureOutcome(trace, event);
                return false;
            }
        }
    }

    private void resetStaleProcessingEvents() {
        LocalDateTime now = LocalDateTime.now();
        teamEventOutboxRepository.resetStaleProcessing(
                TeamEventOutboxEntity.STATUS_PROCESSING,
                TeamEventOutboxEntity.STATUS_PENDING,
                now.minus(PROCESSING_LOCK_TIMEOUT),
                now,
                now,
                "Processing lock expired before dispatch");
    }

    private String routingKeyFor(TeamEventOutboxEntity event) {
        if (isNotification(event)) {
            return queueProperties.getRabbitmq().getNotificationsRoutingKey();
        }
        return queueProperties.getRabbitmq().getTeamTasksRoutingKey();
    }

    private boolean isNotification(TeamEventOutboxEntity event) {
        return AGGREGATE_NOTIFICATION.equalsIgnoreCase(event.getAggregateType());
    }

    private boolean isTeamTask(TeamEventOutboxEntity event) {
        return TeamTaskOutboxWriter.AGGREGATE_TEAM_TASK.equalsIgnoreCase(event.getAggregateType());
    }

    private Map<String, String> payloadFor(TeamEventOutboxEntity event) {
        Map<String, String> payload = new LinkedHashMap<>();
        put(payload, "outboxId", event.getId());
        put(payload, "aggregateType", event.getAggregateType());
        put(payload, "aggregateId", event.getAggregateId());
        put(payload, "eventType", event.getEventType());
        put(payload, "actorUserId", event.getActorUserId());
        put(payload, "targetType", event.getTargetType());
        put(payload, "targetId", event.getTargetId());
        put(payload, "payload", event.getPayload());
        put(payload, "deliveryCount", (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1);
        return payload;
    }

    private void put(Map<String, String> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, String.valueOf(value));
        }
    }

    private void markProcessing(TeamEventOutboxEntity event) {
        LocalDateTime now = LocalDateTime.now();
        event.setStatus(TeamEventOutboxEntity.STATUS_PROCESSING);
        event.setLockedAt(now);
        event.setErrorMessage(null);
        event.setUpdatedAt(now);
    }

    private void markProcessed(TeamEventOutboxEntity event) {
        LocalDateTime now = LocalDateTime.now();
        event.setStatus(TeamEventOutboxEntity.STATUS_PROCESSED);
        event.setProcessedAt(now);
        event.setErrorMessage(null);
        event.setUpdatedAt(now);
    }

    private void markFailedOrRetry(TeamEventOutboxEntity event, RuntimeException error) {
        LocalDateTime now = LocalDateTime.now();
        int retryCount = event.getRetryCount() == null ? 1 : event.getRetryCount() + 1;
        event.setRetryCount(retryCount);
        event.setErrorMessage(StringUtils.hasText(error.getMessage()) ? error.getMessage() : error.getClass().getSimpleName());
        event.setUpdatedAt(now);
        if (retryCount >= queueProperties.getRabbitmq().getMaxDeliveries()) {
            event.setStatus(TeamEventOutboxEntity.STATUS_FAILED);
            event.setProcessedAt(now);
            return;
        }
        event.setStatus(TeamEventOutboxEntity.STATUS_PENDING);
        event.setAvailableAt(now.plusSeconds(Math.min(60, retryCount * 5L)));
    }

    private void recordFailureOutcome(AsyncTraceService.TraceOperation trace, TeamEventOutboxEntity event) {
        if (TeamEventOutboxEntity.STATUS_FAILED.equals(event.getStatus())) {
            trace.deadLetter();
        } else {
            trace.retry();
        }
    }
}
