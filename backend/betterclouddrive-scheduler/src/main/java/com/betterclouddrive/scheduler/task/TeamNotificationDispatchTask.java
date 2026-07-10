package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.TeamNotificationDispatchService;
import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.metrics.QueueMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TeamNotificationDispatchTask {

    private final TeamNotificationDispatchService teamNotificationDispatchService;
    private final QueueProperties queueProperties;
    private final QueueMetrics queueMetrics;

    @Scheduled(fixedDelayString = "${queue.redis.notification-dispatch-interval-ms:2000}")
    public void processQueuedNotifications() {
        long startedAt = queueMetrics.startProcessing();
        try {
            int processed = teamNotificationDispatchService.processQueuedNotifications(queueProperties.getRedis().getReadBatchSize());
            queueMetrics.recordProcessing("notification-dispatch", "success", startedAt);
            if (processed > 0) {
                log.info("Processed {} queued team notifications", processed);
            }
        } catch (RuntimeException e) {
            queueMetrics.recordProcessing("notification-dispatch", "failure", startedAt);
            throw e;
        }
    }
}
