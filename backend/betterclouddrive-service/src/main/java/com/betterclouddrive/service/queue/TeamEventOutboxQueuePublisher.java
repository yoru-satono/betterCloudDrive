package com.betterclouddrive.service.queue;

import com.betterclouddrive.dal.entity.TeamEventOutboxEntity;
import com.betterclouddrive.dal.repository.TeamEventOutboxRepository;
import com.betterclouddrive.service.config.QueueProperties;
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
    private final RedisStreamQueueService redisStreamQueueService;
    private final QueueProperties queueProperties;

    public int publishPending(int batchSize) {
        if (!queueProperties.isEnabled()) {
            return 0;
        }
        resetStaleProcessingEvents();
        int limit = batchSize > 0 ? batchSize : queueProperties.getRedis().getOutboxPublishBatchSize();
        List<TeamEventOutboxEntity> events = teamEventOutboxRepository.findByStatusAndAvailableAtLessThanEqualOrderByIdAsc(
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
        try {
            markProcessing(event);
            teamEventOutboxRepository.save(event);
            String recordId = redisStreamQueueService.publish(streamFor(event), payloadFor(event));
            if (!StringUtils.hasText(recordId)) {
                markFailedOrRetry(event, new IllegalStateException("Redis Stream did not return a record id"));
                teamEventOutboxRepository.save(event);
                return false;
            }
            if (!isNotification(event)) {
                markProcessed(event);
                teamEventOutboxRepository.save(event);
            }
            return true;
        } catch (RuntimeException e) {
            markFailedOrRetry(event, e);
            teamEventOutboxRepository.save(event);
            return false;
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

    private String streamFor(TeamEventOutboxEntity event) {
        if (isNotification(event)) {
            return queueProperties.getRedis().getStreams().getNotifications();
        }
        if (TeamTaskOutboxWriter.AGGREGATE_TEAM_TASK.equalsIgnoreCase(event.getAggregateType())) {
            return queueProperties.getRedis().getStreams().getTeamTasks();
        }
        return queueProperties.getRedis().getStreams().getTeamEvents();
    }

    private boolean isNotification(TeamEventOutboxEntity event) {
        return AGGREGATE_NOTIFICATION.equalsIgnoreCase(event.getAggregateType());
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
        if (retryCount >= queueProperties.getRedis().getMaxDeliveries()) {
            event.setStatus(TeamEventOutboxEntity.STATUS_FAILED);
            event.setProcessedAt(now);
            return;
        }
        event.setStatus(TeamEventOutboxEntity.STATUS_PENDING);
        event.setAvailableAt(now.plusSeconds(Math.min(60, retryCount * 5L)));
    }
}
