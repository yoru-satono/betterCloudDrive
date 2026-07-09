package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.TeamAuditExportTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamAuditExportTaskTest {

    @Mock
    private TeamAuditExportTaskService teamAuditExportTaskService;

    @InjectMocks
    private TeamAuditExportTask task;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(task, "batchSize", 12);
    }

    @Test
    void processPendingAuditExports_shouldDelegateToService() {
        when(teamAuditExportTaskService.processPendingExports(12)).thenReturn(2);

        task.processPendingAuditExports();

        verify(teamAuditExportTaskService).processPendingExports(12);
    }
}
