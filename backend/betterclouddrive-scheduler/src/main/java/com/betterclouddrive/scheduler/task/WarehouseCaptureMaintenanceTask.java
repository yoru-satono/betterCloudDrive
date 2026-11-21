package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.dal.repository.WarehouseEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.betterclouddrive.service.config.WarehouseQueryProperties;
import com.betterclouddrive.service.warehouse.WarehouseReportClient;
import com.betterclouddrive.common.exception.WarehouseUnavailableException;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class WarehouseCaptureMaintenanceTask {
    private final WarehouseEventRepository events;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestAge = new AtomicLong();
    private final AtomicLong queryAvailable = new AtomicLong();
    private final AtomicLong reportReady = new AtomicLong();
    private final AtomicLong publicationAge = new AtomicLong();
    private final WarehouseReportClient reports;
    private final WarehouseQueryProperties properties;

    public WarehouseCaptureMaintenanceTask(WarehouseEventRepository events, MeterRegistry metrics,
            WarehouseReportClient reports, WarehouseQueryProperties properties) {
        this.events = events;
        this.reports = reports;
        this.properties = properties;
        metrics.gauge("bcd_warehouse_pending_events", pending);
        metrics.gauge("bcd_warehouse_oldest_pending_age_seconds", oldestAge);
        metrics.gauge("bcd_warehouse_query_enabled", properties, p -> p.isEnabled() ? 1 : 0);
        metrics.gauge("bcd_warehouse_query_available", queryAvailable);
        metrics.gauge("bcd_warehouse_report_ready", reportReady);
        metrics.gauge("bcd_warehouse_publication_age_seconds", publicationAge);
    }

    @Scheduled(fixedDelayString = "${warehouse.capture-maintenance-ms:60000}")
    public void maintain() {
        var now = Instant.now();
        var state = events.pending();
        pending.set(state.count());
        oldestAge.set(state.oldest() == null ? 0 : Math.max(0, Duration.between(state.oldest(), now).toSeconds()));
        // Only acknowledged staging facts are eligible. Unexported history is never purged.
        events.pruneExported(now.minus(Duration.ofDays(7)));
        if (properties.isEnabled()) {
            try {
                var publication = reports.query("SELECT count() AS count, max(toUnixTimestamp64Milli(published_at)) AS published_at FROM publications", java.util.Map.of()).getFirst();
                queryAvailable.set(1);
                boolean ready = ((Number) publication.get("count")).longValue() > 0;
                reportReady.set(ready ? 1 : 0);
                publicationAge.set(ready ? Math.max(0, (now.toEpochMilli() - ((Number) publication.get("published_at")).longValue()) / 1000) : 0);
            } catch (WarehouseUnavailableException error) {
                queryAvailable.set(0);
            }
        }
    }
}
