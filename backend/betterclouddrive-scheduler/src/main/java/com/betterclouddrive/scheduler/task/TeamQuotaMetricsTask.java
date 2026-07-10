package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.metrics.TeamQuotaMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TeamQuotaMetricsTask {

    private final TeamQuotaMetrics teamQuotaMetrics;

    @Scheduled(fixedDelayString = "${observability.metrics.team-quota-refresh-interval-ms:60000}")
    public void refresh() {
        try {
            int refreshed = teamQuotaMetrics.refreshAllTeams();
            log.debug("Refreshed quota metrics for {} teams", refreshed);
        } catch (Exception e) {
            log.warn("Failed to refresh team quota metrics", e);
        }
    }
}
