package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.metrics.TeamQuotaMetrics;
import com.betterclouddrive.service.metrics.ObservationMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TeamQuotaMetricsTask {

    private final TeamQuotaMetrics teamQuotaMetrics;
    private final ObservationMetrics observationMetrics;

    @Scheduled(fixedDelayString = "${observability.metrics.team-quota-refresh-interval-ms:60000}")
    public void refresh() {
        long startedAt = observationMetrics.startRefresh();
        try {
            int refreshed = teamQuotaMetrics.refreshAllTeams();
            observationMetrics.recordSuccess(ObservationMetrics.Source.TEAM_QUOTA, startedAt);
            log.debug("Refreshed quota metrics for {} teams", refreshed);
        } catch (Exception e) {
            observationMetrics.recordFailure(ObservationMetrics.Source.TEAM_QUOTA, startedAt);
            log.warn("Failed to refresh team quota metrics", e);
        }
    }
}
