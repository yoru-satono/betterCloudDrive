package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.TeamTaskQueueConsumer;
import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.metrics.QueueMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TeamTaskQueueDispatchTask {
    private final TeamTaskQueueConsumer consumer;
    private final QueueProperties properties;
    private final QueueMetrics metrics;

    @Scheduled(fixedDelayString = "${queue.redis.team-task-dispatch-interval-ms:1000}")
    public void dispatch() {
        long started = metrics.startProcessing();
        try {
            consumer.processQueuedTasks(properties.getRedis().getReadBatchSize());
            metrics.recordProcessing("team-task-dispatch", "success", started);
        } catch (RuntimeException e) {
            metrics.recordProcessing("team-task-dispatch", "failure", started);
            throw e;
        }
    }
}
