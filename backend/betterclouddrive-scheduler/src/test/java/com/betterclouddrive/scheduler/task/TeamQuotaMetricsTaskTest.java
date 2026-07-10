package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.metrics.TeamQuotaMetrics;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamQuotaMetricsTaskTest {

    @Test
    void refresh_shouldCollectAllActiveTeamMetrics() {
        TeamQuotaMetrics metrics = mock(TeamQuotaMetrics.class);
        when(metrics.refreshAllTeams()).thenReturn(3);

        new TeamQuotaMetricsTask(metrics).refresh();

        verify(metrics).refreshAllTeams();
    }
}
