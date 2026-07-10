package com.betterclouddrive.service.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class OnlyOfficeSaveMetrics {

    private static final String METRIC_PREFIX = "bcd.onlyoffice.";
    private static final Set<Integer> KNOWN_CALLBACK_STATUSES = Set.of(0, 1, 2, 3, 4, 6, 7);

    private final MeterRegistry meterRegistry;

    public OnlyOfficeSaveMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        KNOWN_CALLBACK_STATUSES.stream().map(String::valueOf)
                .forEach(status -> meterRegistry.counter(METRIC_PREFIX + "callbacks", "status", status));
        meterRegistry.counter(METRIC_PREFIX + "callbacks", "status", "other");
        for (String outcome : List.of("queued", "duplicate", "ignored")) {
            meterRegistry.counter(METRIC_PREFIX + "callback.outcomes", "outcome", outcome);
        }
        for (String outcome : List.of("success", "failure")) {
            meterRegistry.counter(METRIC_PREFIX + "save.results", "outcome", outcome);
            meterRegistry.timer(METRIC_PREFIX + "save.duration", "outcome", outcome);
        }
        meterRegistry.counter(METRIC_PREFIX + "save.retries");
    }

    public void recordCallbackReceived(Integer status) {
        meterRegistry.counter(
                METRIC_PREFIX + "callbacks",
                "status", normalizeStatus(status)).increment();
    }

    public void recordCallbackOutcome(String outcome) {
        meterRegistry.counter(
                METRIC_PREFIX + "callback.outcomes",
                "outcome", normalizeOutcome(outcome)).increment();
    }

    public long startSaveAttempt() {
        return System.nanoTime();
    }

    public void recordSaveSucceeded(long startedAtNanos) {
        recordSaveResult(startedAtNanos, "success");
    }

    public void recordSaveFailed(long startedAtNanos) {
        recordSaveResult(startedAtNanos, "failure");
    }

    public void recordRetry() {
        meterRegistry.counter(METRIC_PREFIX + "save.retries").increment();
    }

    private void recordSaveResult(long startedAtNanos, String outcome) {
        long durationNanos = Math.max(System.nanoTime() - startedAtNanos, 0L);
        meterRegistry.counter(METRIC_PREFIX + "save.results", "outcome", outcome).increment();
        meterRegistry.timer(METRIC_PREFIX + "save.duration", "outcome", outcome)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    private String normalizeStatus(Integer status) {
        return status != null && KNOWN_CALLBACK_STATUSES.contains(status) ? String.valueOf(status) : "other";
    }

    private String normalizeOutcome(String outcome) {
        return outcome == null || outcome.isBlank() ? "unknown" : outcome.trim().toLowerCase();
    }
}
