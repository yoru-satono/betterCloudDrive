package com.betterclouddrive.service.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class OnlyOfficeSaveMetricsTest {

    @Test
    void callbackMetrics_shouldNormalizeStatusAndOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OnlyOfficeSaveMetrics metrics = new OnlyOfficeSaveMetrics(registry);

        metrics.recordCallbackReceived(2);
        metrics.recordCallbackReceived(999);
        metrics.recordCallbackOutcome(" queued ");

        assertThat(registry.get("bcd.onlyoffice.callbacks").tag("status", "2").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("bcd.onlyoffice.callbacks").tag("status", "other").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("bcd.onlyoffice.callback.outcomes").tag("outcome", "queued").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void saveMetrics_shouldRecordResultsDurationAndRetries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OnlyOfficeSaveMetrics metrics = new OnlyOfficeSaveMetrics(registry);
        long startedAt = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(25);

        metrics.recordSaveSucceeded(startedAt);
        metrics.recordSaveFailed(startedAt);
        metrics.recordRetry();

        assertThat(registry.get("bcd.onlyoffice.save.results").tag("outcome", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("bcd.onlyoffice.save.results").tag("outcome", "failure").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("bcd.onlyoffice.save.duration").tag("outcome", "success").timer().count())
                .isEqualTo(1L);
        assertThat(registry.get("bcd.onlyoffice.save.duration").tag("outcome", "success").timer()
                .totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(20.0);
        assertThat(registry.get("bcd.onlyoffice.save.retries").counter().count()).isEqualTo(1.0);
    }
}
