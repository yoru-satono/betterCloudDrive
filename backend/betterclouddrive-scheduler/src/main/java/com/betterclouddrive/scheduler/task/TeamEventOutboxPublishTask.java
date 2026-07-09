package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.queue.TeamEventOutboxQueuePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TeamEventOutboxPublishTask {

    private final TeamEventOutboxQueuePublisher teamEventOutboxQueuePublisher;
    private final QueueProperties queueProperties;

    @Scheduled(fixedDelayString = "${queue.redis.outbox-publish-interval-ms:5000}")
    public void publishPendingOutboxEvents() {
        int published = teamEventOutboxQueuePublisher.publishPending(queueProperties.getRedis().getOutboxPublishBatchSize());
        if (published > 0) {
            log.info("Published {} team outbox events to Redis Streams", published);
        }
    }
}
