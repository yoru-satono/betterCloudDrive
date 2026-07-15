package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.metrics.QueueMetrics;
import com.betterclouddrive.service.queue.TeamEventOutboxQueuePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class TeamEventOutboxPublishTaskTest {

    @Mock
    private TeamEventOutboxQueuePublisher teamEventOutboxQueuePublisher;

    @Mock
    private QueueMetrics queueMetrics;

    private QueueProperties queueProperties;
    private TeamEventOutboxPublishTask task;

    @BeforeEach
    void setUp() {
        queueProperties = new QueueProperties();
        queueProperties.getRabbitmq().setOutboxPublishBatchSize(25);
        task = new TeamEventOutboxPublishTask(teamEventOutboxQueuePublisher, queueProperties, queueMetrics);
    }

    @Test
    void publishPendingOutboxEvents_shouldDelegateToPublisher() {
        task.publishPendingOutboxEvents();

        verify(teamEventOutboxQueuePublisher).publishPending(25);
        verify(queueMetrics).recordProcessing("outbox-publish", "success", 0L);
    }

    @Test
    void publishPendingOutboxEvents_shouldRecordFailure() {
        when(teamEventOutboxQueuePublisher.publishPending(25)).thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(task::publishPendingOutboxEvents)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis down");

        verify(queueMetrics).recordProcessing("outbox-publish", "failure", 0L);
    }
}
