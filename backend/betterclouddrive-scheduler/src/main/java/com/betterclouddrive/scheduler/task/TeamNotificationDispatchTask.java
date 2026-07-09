package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.TeamNotificationDispatchService;
import com.betterclouddrive.service.config.QueueProperties;
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

    @Scheduled(fixedDelayString = "${queue.redis.notification-dispatch-interval-ms:2000}")
    public void processQueuedNotifications() {
        int processed = teamNotificationDispatchService.processQueuedNotifications(queueProperties.getRedis().getReadBatchSize());
        if (processed > 0) {
            log.info("Processed {} queued team notifications", processed);
        }
    }
}
