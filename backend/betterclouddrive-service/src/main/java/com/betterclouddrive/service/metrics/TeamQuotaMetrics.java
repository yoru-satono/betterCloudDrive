package com.betterclouddrive.service.metrics;

import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.repository.TeamRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class TeamQuotaMetrics {

    private static final String METRIC_PREFIX = "bcd.team.quota.";

    private final MeterRegistry meterRegistry;
    private final TeamRepository teamRepository;
    private final ConcurrentMap<Long, QuotaSnapshot> snapshots = new ConcurrentHashMap<>();

    public TeamQuotaMetrics(MeterRegistry meterRegistry, TeamRepository teamRepository) {
        this.meterRegistry = meterRegistry;
        this.teamRepository = teamRepository;
    }

    public int refreshAllTeams() {
        int refreshed = 0;
        for (TeamEntity team : teamRepository.findByDeletedAtIsNull()) {
            if (team.getId() == null || team.getStorageQuota() == null || team.getStorageQuota() <= 0) {
                continue;
            }
            long used = team.getStorageUsed() != null ? team.getStorageUsed() : 0L;
            int ratioPercent = (int) Math.floor(used * 100.0 / team.getStorageQuota());
            int threshold = ratioPercent >= 100 ? 100 : ratioPercent >= 90 ? 90 : ratioPercent >= 80 ? 80 : 0;
            recordUsage(team.getId(), used, team.getStorageQuota(), threshold);
            refreshed++;
        }
        return refreshed;
    }

    public void recordUsage(Long teamId, long usedBytes, long quotaBytes, int thresholdPercent) {
        if (teamId == null) {
            return;
        }
        QuotaSnapshot snapshot = snapshots.computeIfAbsent(teamId, this::registerSnapshot);
        snapshot.update(usedBytes, quotaBytes, thresholdPercent);
    }

    public void recordAlertTriggered(int thresholdPercent) {
        meterRegistry.counter(
                METRIC_PREFIX + "alerts",
                "threshold", String.valueOf(thresholdPercent)).increment();
    }

    public void recordNotificationFailure(String channel) {
        meterRegistry.counter(
                METRIC_PREFIX + "notification.failures",
                "channel", normalizeChannel(channel)).increment();
    }

    private QuotaSnapshot registerSnapshot(Long teamId) {
        String teamTag = String.valueOf(teamId);
        QuotaSnapshot snapshot = new QuotaSnapshot();
        Gauge.builder(METRIC_PREFIX + "used.bytes", snapshot, value -> value.usedBytes.get())
                .description("Current team storage usage")
                .baseUnit("bytes")
                .tag("team.id", teamTag)
                .register(meterRegistry);
        Gauge.builder(METRIC_PREFIX + "limit.bytes", snapshot, value -> value.quotaBytes.get())
                .description("Configured team storage quota")
                .baseUnit("bytes")
                .tag("team.id", teamTag)
                .register(meterRegistry);
        Gauge.builder(METRIC_PREFIX + "usage.ratio", snapshot, QuotaSnapshot::usageRatio)
                .description("Team storage usage ratio from 0 to 1")
                .tag("team.id", teamTag)
                .register(meterRegistry);
        Gauge.builder(METRIC_PREFIX + "threshold.percent", snapshot, value -> value.thresholdPercent.get())
                .description("Current reached team quota alert threshold")
                .baseUnit("percent")
                .tag("team.id", teamTag)
                .register(meterRegistry);
        return snapshot;
    }

    private String normalizeChannel(String channel) {
        return channel == null || channel.isBlank() ? "unknown" : channel.trim().toLowerCase();
    }

    private static final class QuotaSnapshot {

        private final AtomicLong usedBytes = new AtomicLong();
        private final AtomicLong quotaBytes = new AtomicLong();
        private final AtomicLong thresholdPercent = new AtomicLong();

        private void update(long used, long quota, int threshold) {
            usedBytes.set(Math.max(used, 0L));
            quotaBytes.set(Math.max(quota, 0L));
            thresholdPercent.set(Math.max(threshold, 0));
        }

        private double usageRatio() {
            long quota = quotaBytes.get();
            return quota > 0 ? usedBytes.get() / (double) quota : 0.0;
        }
    }
}
