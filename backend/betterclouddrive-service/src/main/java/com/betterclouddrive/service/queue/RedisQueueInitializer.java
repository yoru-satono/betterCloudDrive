package com.betterclouddrive.service.queue;

import com.betterclouddrive.service.config.QueueProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisQueueInitializer implements ApplicationRunner {

    private final QueueProperties queueProperties;
    private final RedisStreamQueueService redisStreamQueueService;

    @Override
    public void run(ApplicationArguments args) {
        initialize();
    }

    public void initialize() {
        if (!queueProperties.isEnabled()) {
            log.info("Redis Stream queue initialization skipped because queue.enabled=false");
            return;
        }
        try {
            QueueProperties.Redis redis = queueProperties.getRedis();
            redisStreamQueueService.ensureConsumerGroup(redis.getStreams().getTeamEvents(), redis.getGroups().getTeamEvents());
            redisStreamQueueService.ensureConsumerGroup(redis.getStreams().getNotifications(), redis.getGroups().getNotifications());
            redisStreamQueueService.ensureConsumerGroup(redis.getStreams().getTeamTasks(), redis.getGroups().getTeamTasks());
            log.info("Redis Stream queue consumer groups are ready");
        } catch (RuntimeException e) {
            log.error("Failed to initialize Redis Stream queue consumer groups", e);
        }
    }
}
