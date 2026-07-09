package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.TeamNotificationDispatchService;
import com.betterclouddrive.service.config.QueueProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TeamNotificationDispatchTaskTest {

    @Mock
    private TeamNotificationDispatchService teamNotificationDispatchService;

    private QueueProperties queueProperties;
    private TeamNotificationDispatchTask task;

    @BeforeEach
    void setUp() {
        queueProperties = new QueueProperties();
        queueProperties.getRedis().setReadBatchSize(25);
        task = new TeamNotificationDispatchTask(teamNotificationDispatchService, queueProperties);
    }

    @Test
    void processQueuedNotifications_shouldDelegateToDispatchService() {
        task.processQueuedNotifications();

        verify(teamNotificationDispatchService).processQueuedNotifications(25);
    }
}
