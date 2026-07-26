package com.betterclouddrive.service.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationMetricsTest {

    @Test
    void registersBoundedSourcesAndRecordsSuccessFailureAndDuration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);
        ObservationMetrics metrics = new ObservationMetrics(registry, clock);

        for (ObservationMetrics.Source source : ObservationMetrics.Source.values()) {
            assertThat(registry.get("bcd.observation.last.success.timestamp")
                    .tag("source", source.tag()).gauge().value()).isZero();
        }

        metrics.recordSuccess(ObservationMetrics.Source.TEAM_QUOTA, metrics.startRefresh());
        metrics.recordFailure(ObservationMetrics.Source.QUEUE_RABBITMQ, metrics.startRefresh());
        metrics.recordSuccess(ObservationMetrics.Source.QUEUE_RABBITMQ, metrics.startRefresh());

        assertThat(registry.get("bcd.observation.last.success.timestamp")
                .tag("source", "team-quota").gauge().value()).isEqualTo(1_700_000_000D);
        assertThat(registry.get("bcd.observation.refresh.failures")
                .tag("source", "queue-rabbitmq").counter().count()).isEqualTo(1D);
        assertThat(registry.get("bcd.observation.last.success.timestamp")
                .tag("source", "queue-rabbitmq").gauge().value()).isEqualTo(1_700_000_000D);
        assertThat(registry.get("bcd.observation.refresh.duration")
                .tag("source", "team-quota").tag("outcome", "success").timer().count()).isEqualTo(1L);
        assertThat(registry.get("bcd.observation.refresh.duration")
                .tag("source", "queue-rabbitmq").tag("outcome", "failure").timer().count()).isEqualTo(1L);
        assertThat(registry.get("bcd.observation.refresh.duration")
                .tag("source", "queue-rabbitmq").tag("outcome", "success").timer().count()).isEqualTo(1L);
        assertThat(registry.find("bcd.observation.last.success.timestamp").gauges()).hasSize(3);
    }
}
