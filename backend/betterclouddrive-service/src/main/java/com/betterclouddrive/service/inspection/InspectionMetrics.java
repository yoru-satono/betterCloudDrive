package com.betterclouddrive.service.inspection;

import com.betterclouddrive.dal.repository.InspectionFindingRepository;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository;
import com.betterclouddrive.dal.repository.StorageInspectionRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class InspectionMetrics {
    private final MeterRegistry registry;
    private final StorageInspectionRepository runs;
    private final InspectionFindingRepository findings;
    private final QuotaAccountingRepository accounting;
    private final InspectionProperties properties;
    private final AtomicLong lastComplete = new AtomicLong();
    private final AtomicLong ready = new AtomicLong();
    private final AtomicLong actionable = new AtomicLong();
    private final Map<String, AtomicLong> counts = new HashMap<>();

    public InspectionMetrics(MeterRegistry registry, StorageInspectionRepository runs, InspectionFindingRepository findings,
                             QuotaAccountingRepository accounting, InspectionProperties properties) {
        this.registry = registry; this.runs = runs; this.findings = findings; this.accounting = accounting; this.properties = properties;
        Gauge.builder("bcd.storage.inspection.enabled", properties, p -> p.isEnabled() && p.isScheduled() ? 1 : 0).register(registry);
        Gauge.builder("bcd.storage.quota.accounting.ready", ready, AtomicLong::doubleValue).register(registry);
        Gauge.builder("bcd.storage.inspection.last.complete.timestamp", lastComplete, AtomicLong::doubleValue).register(registry);
        Gauge.builder("bcd.storage.inspection.actionable.findings", actionable, AtomicLong::doubleValue).register(registry);
        for (String type : InspectionRules.TYPES) for (String status : new String[]{"SUSPECTED", "CONFIRMED", "RESOLVED"}) {
            var count = new AtomicLong(); counts.put(type + ":" + status, count);
            Gauge.builder("bcd.storage.inspection.findings", count, AtomicLong::doubleValue).tags("type", type, "status", status).register(registry);
        }
        for (String kind : new String[]{"FULL", "RECHECK"}) for (String status : new String[]{"SUCCEEDED", "PARTIAL", "FAILED", "CANCELED"}) {
            registry.counter("bcd.storage.inspection.runs", "kind", kind, "status", status);
            registry.timer("bcd.storage.inspection.duration", "kind", kind, "status", status);
        }
        registry.counter("bcd.storage.quota.repairs");
    }

    public void refresh() {
        ready.set(accounting.ready() ? 1 : 0);
        lastComplete.set(runs.latestComplete().map(run -> run.finishedAt().getEpochSecond()).orElse(0L));
        counts.values().forEach(value -> value.set(0));
        actionable.set(0);
        for (var row : findings.counts()) {
            var count = counts.get(row.get("type") + ":" + row.get("status"));
            long value = ((Number) row.get("count")).longValue();
            if (count != null) count.addAndGet(value);
            if ("CONFIRMED".equals(row.get("status")) && "ERROR".equals(row.get("severity")) && !"IGNORED".equals(row.get("review_status"))) {
                actionable.addAndGet(value);
            }
        }
    }

    public void completed(String kind, String status, Instant started) {
        registry.counter("bcd.storage.inspection.runs", "kind", kind, "status", status).increment();
        Duration elapsed = Duration.between(started, Instant.now());
        registry.timer("bcd.storage.inspection.duration", "kind", kind, "status", status).record(elapsed.isNegative() ? Duration.ZERO : elapsed);
        if ("FULL".equals(kind) && "SUCCEEDED".equals(status)) lastComplete.set(Instant.now().getEpochSecond());
    }

    public void repairedAfterCommit() {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { registry.counter("bcd.storage.quota.repairs").increment(); }
                });
    }
}
