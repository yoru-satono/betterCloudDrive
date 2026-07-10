package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.TeamNotificationDispatchService;
import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.metrics.QueueMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class TeamNotificationDispatchTaskTest {

    @Mock
    private TeamNotificationDispatchService teamNotificationDispatchService;

    @Mock
    private QueueMetrics queueMetrics;

    private QueueProperties queueProperties;
    private TeamNotificationDispatchTask task;

    @BeforeEach
    void setUp() {
        queueProperties = new QueueProperties();
        queueProperties.getRedis().setReadBatchSize(25);
        task = new TeamNotificationDispatchTask(teamNotificationDispatchService, queueProperties, queueMetrics);
    }

    @Test
    void processQueuedNotifications_shouldDelegateToDispatchService() {
        task.processQueuedNotifications();

        verify(teamNotificationDispatchService).processQueuedNotifications(25);
        verify(queueMetrics).recordProcessing("notification-dispatch", "success", 0L);
    }

    @Test
    void processQueuedNotifications_shouldRecordFailure() {
        when(teamNotificationDispatchService.processQueuedNotifications(25))
                .thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(task::processQueuedNotifications)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database down");

        verify(queueMetrics).recordProcessing("notification-dispatch", "failure", 0L);
    }
}
