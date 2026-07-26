package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.metrics.TeamQuotaMetrics;
import com.betterclouddrive.service.metrics.ObservationMetrics;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamQuotaMetricsTaskTest {

    @Test
    void refresh_shouldCollectAllActiveTeamMetrics() {
        TeamQuotaMetrics metrics = mock(TeamQuotaMetrics.class);
        ObservationMetrics observationMetrics = mock(ObservationMetrics.class);
        when(metrics.refreshAllTeams()).thenReturn(3);
        when(observationMetrics.startRefresh()).thenReturn(100L);

        new TeamQuotaMetricsTask(metrics, observationMetrics).refresh();

        verify(metrics).refreshAllTeams();
        verify(observationMetrics).recordSuccess(ObservationMetrics.Source.TEAM_QUOTA, 100L);
    }

    @Test
    void refresh_shouldRecordFailureWithoutEscapingTheScheduler() {
        TeamQuotaMetrics metrics = mock(TeamQuotaMetrics.class);
        ObservationMetrics observationMetrics = mock(ObservationMetrics.class);
        when(metrics.refreshAllTeams()).thenThrow(new IllegalStateException("database unavailable"));

        new TeamQuotaMetricsTask(metrics, observationMetrics).refresh();

        verify(observationMetrics).recordFailure(ObservationMetrics.Source.TEAM_QUOTA, 0L);
    }
}
