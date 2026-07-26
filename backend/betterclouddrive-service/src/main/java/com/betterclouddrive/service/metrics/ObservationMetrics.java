package com.betterclouddrive.service.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ObservationMetrics {

    private static final String LAST_SUCCESS_METRIC = "bcd.observation.last.success.timestamp";
    private static final String FAILURE_METRIC = "bcd.observation.refresh.failures";
    private static final String DURATION_METRIC = "bcd.observation.refresh.duration";

    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<Source, AtomicLong> lastSuccess = new EnumMap<>(Source.class);

    @Autowired
    public ObservationMetrics(MeterRegistry registry) {
        this(registry, Clock.systemUTC());
    }

    ObservationMetrics(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        for (Source source : Source.values()) {
            AtomicLong timestamp = new AtomicLong();
            lastSuccess.put(source, timestamp);
            Gauge.builder(LAST_SUCCESS_METRIC, timestamp, AtomicLong::get)
                    .description("Unix timestamp of the last successful observability source refresh")
                    .baseUnit("seconds")
                    .tag("source", source.tag())
                    .register(registry);
            registry.counter(FAILURE_METRIC, "source", source.tag());
            for (String outcome : new String[]{"success", "failure"}) {
                registry.timer(DURATION_METRIC, "source", source.tag(), "outcome", outcome);
            }
        }
    }

    public long startRefresh() {
        return System.nanoTime();
    }

    public void recordSuccess(Source source, long startedAtNanos) {
        lastSuccess.get(source).set(clock.instant().getEpochSecond());
        recordDuration(source, "success", startedAtNanos);
    }

    public void recordFailure(Source source, long startedAtNanos) {
        registry.counter(FAILURE_METRIC, "source", source.tag()).increment();
        recordDuration(source, "failure", startedAtNanos);
    }

    private void recordDuration(Source source, String outcome, long startedAtNanos) {
        Timer timer = registry.timer(DURATION_METRIC, "source", source.tag(), "outcome", outcome);
        timer.record(Math.max(0L, System.nanoTime() - startedAtNanos), TimeUnit.NANOSECONDS);
    }

    public enum Source {
        TEAM_QUOTA("team-quota"),
        QUEUE_RABBITMQ("queue-rabbitmq"),
        QUEUE_DATABASE("queue-database");

        private final String tag;

        Source(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }
}
