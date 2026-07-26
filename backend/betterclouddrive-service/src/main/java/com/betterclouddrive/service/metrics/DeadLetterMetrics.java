package com.betterclouddrive.service.metrics;

import com.betterclouddrive.dal.repository.DeadLetterRecordRepository;
import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class DeadLetterMetrics {
    private static final List<String> QUEUES = List.of("notifications", "team-tasks", "unknown");
    private static final List<String> CATEGORIES =
            List.of("invalid-payload", "missing-record", "delivery-exhausted", "unknown");
    private static final List<String> ACTIONS = List.of("replay", "discard");
    private static final List<String> OUTCOMES = List.of("success", "failure");

    private final MeterRegistry registry;
    private final DeadLetterRecordRepository repository;
    private final Map<String, AtomicLong> open = new LinkedHashMap<>();

    public DeadLetterMetrics(MeterRegistry registry, DeadLetterRecordRepository repository) {
        this.registry = registry;
        this.repository = repository;
        for (String queue : QUEUES) {
            for (String category : CATEGORIES) {
                String key = queue + ":" + category;
                AtomicLong value = new AtomicLong();
                open.put(key, value);
                Gauge.builder("bcd.queue.dead_letters", value, AtomicLong::get)
                        .tags("queue", queue, "category", category)
                        .description("Open persisted dead-letter records")
                        .register(registry);
            }
        }
        for (String action : ACTIONS) for (String outcome : OUTCOMES) {
            registry.counter("bcd.queue.dead_letter.actions", "action", action, "outcome", outcome);
        }
    }

    public void refresh() {
        open.values().forEach(value -> value.set(0));
        for (Object[] row : repository.countOpenGrouped()) {
            AtomicLong value = open.get(row[0] + ":" + row[1]);
            if (value != null) value.set(((Number) row[2]).longValue());
        }
    }

    public void record(String action, String outcome) {
        String safeAction = ACTIONS.contains(action) ? action : "replay";
        String safeOutcome = OUTCOMES.contains(outcome) ? outcome : "failure";
        registry.counter("bcd.queue.dead_letter.actions", "action", safeAction, "outcome", safeOutcome).increment();
    }
}
