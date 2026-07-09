package com.betterclouddrive.service.queue;

import com.betterclouddrive.service.config.QueueProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RedisQueueInitializerTest {

    @Mock
    private RedisStreamQueueService redisStreamQueueService;

    private QueueProperties queueProperties;
    private RedisQueueInitializer initializer;

    @BeforeEach
    void setUp() {
        queueProperties = new QueueProperties();
        initializer = new RedisQueueInitializer(queueProperties, redisStreamQueueService);
    }

    @Test
    void initialize_shouldEnsureDefaultConsumerGroups() {
        initializer.initialize();

        verify(redisStreamQueueService).ensureConsumerGroup("bcd:stream:team-events", "bcd-team-event-workers");
        verify(redisStreamQueueService).ensureConsumerGroup("bcd:stream:notifications", "bcd-notification-workers");
        verify(redisStreamQueueService).ensureConsumerGroup("bcd:stream:team-tasks", "bcd-team-task-workers");
    }

    @Test
    void initialize_shouldSkipWhenQueueDisabled() {
        queueProperties.setEnabled(false);

        initializer.initialize();

        verify(redisStreamQueueService, never()).ensureConsumerGroup(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
