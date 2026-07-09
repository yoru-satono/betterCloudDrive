package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.queue.TeamEventOutboxQueuePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TeamEventOutboxPublishTaskTest {

    @Mock
    private TeamEventOutboxQueuePublisher teamEventOutboxQueuePublisher;

    private QueueProperties queueProperties;
    private TeamEventOutboxPublishTask task;

    @BeforeEach
    void setUp() {
        queueProperties = new QueueProperties();
        queueProperties.getRedis().setOutboxPublishBatchSize(25);
        task = new TeamEventOutboxPublishTask(teamEventOutboxQueuePublisher, queueProperties);
    }

    @Test
    void publishPendingOutboxEvents_shouldDelegateToPublisher() {
        task.publishPendingOutboxEvents();

        verify(teamEventOutboxQueuePublisher).publishPending(25);
    }
}
