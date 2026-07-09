package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.TeamAuditExportTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamTaskResultCleanupTaskTest {

    @Mock
    private TeamAuditExportTaskService teamAuditExportTaskService;

    @InjectMocks
    private TeamTaskResultCleanupTask task;

    @Test
    void cleanupExpiredTaskResults_shouldDelegateToService() {
        when(teamAuditExportTaskService.cleanupExpiredResults()).thenReturn(1);

        task.cleanupExpiredTaskResults();

        verify(teamAuditExportTaskService).cleanupExpiredResults();
    }
}
