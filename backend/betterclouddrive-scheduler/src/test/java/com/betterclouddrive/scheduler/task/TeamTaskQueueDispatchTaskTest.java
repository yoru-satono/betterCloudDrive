package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.TeamTaskQueueConsumer;
import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.metrics.QueueMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamTaskQueueDispatchTaskTest {
    @Mock TeamTaskQueueConsumer consumer;
    @Mock QueueMetrics metrics;
    QueueProperties properties;
    TeamTaskQueueDispatchTask task;

    @BeforeEach void setUp() {
        properties = new QueueProperties();
        properties.getRedis().setReadBatchSize(12);
        task = new TeamTaskQueueDispatchTask(consumer, properties, metrics);
    }

    @Test void dispatchesConfiguredBatchAndRecordsMetrics() {
        task.dispatch();
        verify(consumer).processQueuedTasks(12);
        verify(metrics).recordProcessing("team-task-dispatch", "success", 0L);
    }

    @Test void recordsConsumerFailure() {
        when(consumer.processQueuedTasks(12)).thenThrow(new IllegalStateException("redis down"));
        assertThatThrownBy(task::dispatch).isInstanceOf(IllegalStateException.class);
        verify(metrics).recordProcessing("team-task-dispatch", "failure", 0L);
    }
}
