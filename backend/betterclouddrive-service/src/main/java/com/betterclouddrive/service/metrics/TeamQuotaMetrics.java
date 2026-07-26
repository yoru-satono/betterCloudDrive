package com.betterclouddrive.service.metrics;

import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.repository.TeamRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class TeamQuotaMetrics {

    private static final String METRIC_PREFIX = "bcd.team.quota.";
    private static final Set<String> FAILURE_CHANNELS = Set.of("queue", "direct", "email");

    private final MeterRegistry meterRegistry;
    private final TeamRepository teamRepository;
    private final ConcurrentMap<Long, QuotaSnapshot> snapshots = new ConcurrentHashMap<>();

    public TeamQuotaMetrics(MeterRegistry meterRegistry, TeamRepository teamRepository) {
        this.meterRegistry = meterRegistry;
        this.teamRepository = teamRepository;
        Gauge.builder(METRIC_PREFIX + "used.bytes", snapshots, values -> total(values, true))
                .description("Total storage used by active teams")
                .baseUnit("bytes")
                .register(meterRegistry);
        Gauge.builder(METRIC_PREFIX + "limit.bytes", snapshots, values -> total(values, false))
                .description("Total storage quota configured for active teams")
                .baseUnit("bytes")
                .register(meterRegistry);
        for (String bucket : new String[]{"under_80", "80_90", "90_100", "at_or_over_100"}) {
            Gauge.builder(METRIC_PREFIX + "teams", snapshots, values -> countBucket(values, bucket))
                    .description("Number of active teams grouped by quota usage")
                    .tag("usage", bucket)
                    .register(meterRegistry);
        }
        for (String threshold : new String[]{"80", "90", "100", "other"}) {
            meterRegistry.counter(METRIC_PREFIX + "alerts", "threshold", threshold);
        }
        for (String channel : new String[]{"queue", "direct", "email", "unknown"}) {
            meterRegistry.counter(METRIC_PREFIX + "notification.failures", "channel", channel);
        }
    }

    public synchronized int refreshAllTeams() {
        ConcurrentMap<Long, QuotaSnapshot> refreshedSnapshots = new ConcurrentHashMap<>();
        int refreshed = 0;
        for (TeamEntity team : teamRepository.findByDeletedAtIsNull()) {
            if (team.getId() == null || team.getStorageQuota() == null || team.getStorageQuota() <= 0) {
                continue;
            }
            long used = team.getStorageUsed() != null ? team.getStorageUsed() : 0L;
            int ratioPercent = (int) Math.floor(used * 100.0 / team.getStorageQuota());
            int threshold = ratioPercent >= 100 ? 100 : ratioPercent >= 90 ? 90 : ratioPercent >= 80 ? 80 : 0;
            refreshedSnapshots.put(team.getId(), new QuotaSnapshot(used, team.getStorageQuota(), threshold));
            refreshed++;
        }
        snapshots.clear();
        snapshots.putAll(refreshedSnapshots);
        return refreshed;
    }

    public void recordUsage(Long teamId, long usedBytes, long quotaBytes, int thresholdPercent) {
        if (teamId == null) {
            return;
        }
        QuotaSnapshot snapshot = snapshots.computeIfAbsent(teamId, ignored -> new QuotaSnapshot());
        snapshot.update(usedBytes, quotaBytes, thresholdPercent);
    }

    public void recordAlertTriggered(int thresholdPercent) {
        meterRegistry.counter(
                METRIC_PREFIX + "alerts",
                "threshold", normalizeThreshold(thresholdPercent)).increment();
    }

    public void recordNotificationFailure(String channel) {
        meterRegistry.counter(
                METRIC_PREFIX + "notification.failures",
                "channel", normalizeChannel(channel)).increment();
    }

    private static double total(Map<Long, QuotaSnapshot> values, boolean used) {
        return values.values().stream()
                .mapToLong(value -> used ? value.usedBytes.get() : value.quotaBytes.get())
                .sum();
    }

    private static double countBucket(Map<Long, QuotaSnapshot> values, String bucket) {
        return values.values().stream().filter(value -> bucket.equals(value.usageBucket())).count();
    }

    private String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return "unknown";
        }
        String normalized = channel.trim().toLowerCase();
        return FAILURE_CHANNELS.contains(normalized) ? normalized : "unknown";
    }

    private String normalizeThreshold(int thresholdPercent) {
        return switch (thresholdPercent) {
            case 80, 90, 100 -> String.valueOf(thresholdPercent);
            default -> "other";
        };
    }

    private static final class QuotaSnapshot {

        private final AtomicLong usedBytes = new AtomicLong();
        private final AtomicLong quotaBytes = new AtomicLong();
        private final AtomicLong thresholdPercent = new AtomicLong();

        private QuotaSnapshot() {
        }

        private QuotaSnapshot(long used, long quota, int threshold) {
            update(used, quota, threshold);
        }

        private void update(long used, long quota, int threshold) {
            usedBytes.set(Math.max(used, 0L));
            quotaBytes.set(Math.max(quota, 0L));
            thresholdPercent.set(Math.max(threshold, 0));
        }

        private String usageBucket() {
            long quota = quotaBytes.get();
            double ratio = quota > 0 ? usedBytes.get() / (double) quota : 0.0;
            if (ratio >= 1.0) return "at_or_over_100";
            if (ratio >= 0.9) return "90_100";
            if (ratio >= 0.8) return "80_90";
            return "under_80";
        }
    }
}
