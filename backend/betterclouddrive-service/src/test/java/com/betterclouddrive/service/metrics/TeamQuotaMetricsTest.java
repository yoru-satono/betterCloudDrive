package com.betterclouddrive.service.metrics;

import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.repository.TeamRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamQuotaMetricsTest {

    @Test
    void recordUsage_shouldExposeBoundedAggregateTeamQuotaGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TeamQuotaMetrics metrics = new TeamQuotaMetrics(registry, mock(TeamRepository.class));

        metrics.recordUsage(10L, 850L, 1000L, 80);

        assertThat(registry.get("bcd.team.quota.used.bytes").gauge().value())
                .isEqualTo(850.0);
        assertThat(registry.get("bcd.team.quota.limit.bytes").gauge().value())
                .isEqualTo(1000.0);
        assertThat(registry.get("bcd.team.quota.teams").tag("usage", "80_90").gauge().value())
                .isEqualTo(1.0);
    }

    @Test
    void counters_shouldSeparateThresholdsAndFailureChannels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TeamQuotaMetrics metrics = new TeamQuotaMetrics(registry, mock(TeamRepository.class));

        metrics.recordAlertTriggered(90);
        metrics.recordAlertTriggered(90);
        metrics.recordAlertTriggered(123);
        metrics.recordNotificationFailure("queue");
        metrics.recordNotificationFailure("dynamic-channel");

        assertThat(registry.get("bcd.team.quota.alerts").tag("threshold", "90").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("bcd.team.quota.notification.failures").tag("channel", "queue").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("bcd.team.quota.alerts").tag("threshold", "other").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("bcd.team.quota.notification.failures").tag("channel", "unknown").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("bcd.team.quota.alerts").counters()).hasSize(4);
        assertThat(registry.find("bcd.team.quota.notification.failures").counters()).hasSize(4);
    }

    @Test
    void refreshAllTeams_shouldRegisterCurrentActiveTeamUsage() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TeamRepository teamRepository = mock(TeamRepository.class);
        when(teamRepository.findByDeletedAtIsNull()).thenReturn(List.of(
                TeamEntity.builder().id(10L).storageUsed(950L).storageQuota(1000L).build(),
                TeamEntity.builder().id(11L).storageUsed(10L).storageQuota(0L).build()));
        TeamQuotaMetrics metrics = new TeamQuotaMetrics(registry, teamRepository);

        int refreshed = metrics.refreshAllTeams();

        assertThat(refreshed).isEqualTo(1);
        assertThat(registry.get("bcd.team.quota.teams").tag("usage", "90_100").gauge().value())
                .isEqualTo(1.0);
    }
}
