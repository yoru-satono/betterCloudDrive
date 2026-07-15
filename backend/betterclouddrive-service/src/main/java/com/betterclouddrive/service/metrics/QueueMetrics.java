package com.betterclouddrive.service.metrics;

import com.betterclouddrive.dal.entity.TeamEventOutboxEntity;
import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.dal.repository.TeamEventOutboxRepository;
import com.betterclouddrive.dal.repository.TeamTaskRepository;
import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.queue.RedisStreamQueueService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class QueueMetrics {

    private static final String METRIC_PREFIX = "bcd.queue.";
    private static final List<String> PROCESSING_OPERATIONS = List.of("outbox-publish", "notification-dispatch", "team-task-dispatch");
    private static final List<String> OUTCOMES = List.of("success", "failure");

    private final MeterRegistry meterRegistry;
    private final RedisStreamQueueService redisStreamQueueService;
    private final TeamEventOutboxRepository teamEventOutboxRepository;
    private final TeamTaskRepository teamTaskRepository;
    private final QueueProperties queueProperties;
    private final Map<String, AtomicLong> streamLengths = new LinkedHashMap<>();
    private final Map<String, AtomicLong> consumerPending = new LinkedHashMap<>();
    private final Map<String, AtomicLong> recordPending = new LinkedHashMap<>();
    private final Map<String, AtomicLong> recordRetries = new LinkedHashMap<>();
    private final Map<String, AtomicBoolean> warningStates = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> observationAvailability = new LinkedHashMap<>();

    public QueueMetrics(
            MeterRegistry meterRegistry,
            RedisStreamQueueService redisStreamQueueService,
            TeamEventOutboxRepository teamEventOutboxRepository,
            TeamTaskRepository teamTaskRepository,
            QueueProperties queueProperties) {
        this.meterRegistry = meterRegistry;
        this.redisStreamQueueService = redisStreamQueueService;
        this.teamEventOutboxRepository = teamEventOutboxRepository;
        this.teamTaskRepository = teamTaskRepository;
        this.queueProperties = queueProperties;
        registerGauges();
        registerCountersAndTimers();
    }

    public QueueSnapshot refresh() {
        refreshRedisSafely();
        refreshDatabaseSafely();
        warnOnBacklog();
        return snapshot();
    }

    public long startProcessing() {
        return System.nanoTime();
    }

    public void recordProcessing(String operation, String outcome, long startedAtNanos) {
        String normalizedOperation = normalize(operation, PROCESSING_OPERATIONS);
        String normalizedOutcome = normalize(outcome, OUTCOMES);
        long durationNanos = Math.max(System.nanoTime() - startedAtNanos, 0L);
        meterRegistry.counter(
                METRIC_PREFIX + "processing.results",
                "operation", normalizedOperation,
                "outcome", normalizedOutcome).increment();
        meterRegistry.timer(
                METRIC_PREFIX + "processing.duration",
                "operation", normalizedOperation,
                "outcome", normalizedOutcome).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordDeadLetter(String sourceStream) {
        meterRegistry.counter(
                METRIC_PREFIX + "deadlettered",
                "stream", streamLabel(sourceStream)).increment();
    }

    public void recordTaskClaim(String taskType, int claimed) {
        meterRegistry.counter(METRIC_PREFIX + "team_tasks.claimed", "type", taskType).increment(Math.max(claimed, 0));
    }

    public void recordDuplicateTaskWakeup(String taskType) {
        meterRegistry.counter(METRIC_PREFIX + "team_tasks.duplicate_wakeups", "type", taskType).increment();
    }

    public void recordTimedOutTaskRecovery(int recovered) {
        meterRegistry.counter(METRIC_PREFIX + "team_tasks.timeout_recovered").increment(Math.max(recovered, 0));
    }

    private void registerGauges() {
        for (String stream : List.of("team-events", "notifications", "team-tasks", "dead-letter")) {
            streamLengths.put(stream, registerGauge("stream.length", "stream", stream,
                    "Current Redis Stream length"));
        }
        QueueProperties.Groups groups = queueProperties.getRedis().getGroups();
        consumerPending.put("team-events", registerPendingGauge("team-events", groups.getTeamEvents()));
        consumerPending.put("notifications", registerPendingGauge("notifications", groups.getNotifications()));
        consumerPending.put("team-tasks", registerPendingGauge("team-tasks", groups.getTeamTasks()));
        for (String source : List.of("outbox", "team-tasks")) {
            recordPending.put(source, registerGauge("records.pending", "source", source,
                    "Pending records in the durable queue tables"));
            recordRetries.put(source, registerGauge("records.retry", "source", source,
                    "Pending records that have already been retried"));
        }
        for (String source : List.of("redis", "database")) {
            observationAvailability.put(source, registerGauge("observation.available", "source", source,
                    "Whether the queue metrics source was available during the last refresh"));
        }
    }

    private void registerCountersAndTimers() {
        for (String stream : List.of("team-events", "notifications", "team-tasks", "unknown")) {
            meterRegistry.counter(METRIC_PREFIX + "deadlettered", "stream", stream);
        }
        for (String operation : PROCESSING_OPERATIONS) {
            for (String outcome : OUTCOMES) {
                meterRegistry.counter(METRIC_PREFIX + "processing.results", "operation", operation, "outcome", outcome);
                meterRegistry.timer(METRIC_PREFIX + "processing.duration", "operation", operation, "outcome", outcome);
            }
        }
    }

    private AtomicLong registerPendingGauge(String stream, String group) {
        AtomicLong value = new AtomicLong();
        Gauge.builder(METRIC_PREFIX + "consumer.pending", value, AtomicLong::get)
                .description("Messages delivered but not acknowledged by a Redis Stream consumer group")
                .tag("stream", stream)
                .tag("group", group)
                .register(meterRegistry);
        return value;
    }

    private AtomicLong registerGauge(String metric, String tagName, String tagValue, String description) {
        AtomicLong value = new AtomicLong();
        Gauge.builder(METRIC_PREFIX + metric, value, AtomicLong::get)
                .description(description)
                .tag(tagName, tagValue)
                .register(meterRegistry);
        return value;
    }

    private void refreshRedisSafely() {
        if (!queueProperties.isEnabled()) {
            streamLengths.values().forEach(value -> value.set(0L));
            consumerPending.values().forEach(value -> value.set(0L));
            setAvailability("redis", true, null);
            return;
        }
        QueueProperties.Redis redis = queueProperties.getRedis();
        try {
            set(streamLengths, "team-events", redisStreamQueueService.streamLength(redis.getStreams().getTeamEvents()));
            set(streamLengths, "notifications", redisStreamQueueService.streamLength(redis.getStreams().getNotifications()));
            set(streamLengths, "team-tasks", redisStreamQueueService.streamLength(redis.getStreams().getTeamTasks()));
            set(streamLengths, "dead-letter", redisStreamQueueService.streamLength(redis.getStreams().getDeadLetter()));
            set(consumerPending, "team-events", redisStreamQueueService.pendingCount(
                    redis.getStreams().getTeamEvents(), redis.getGroups().getTeamEvents()));
            set(consumerPending, "notifications", redisStreamQueueService.pendingCount(
                    redis.getStreams().getNotifications(), redis.getGroups().getNotifications()));
            set(consumerPending, "team-tasks", redisStreamQueueService.pendingCount(
                    redis.getStreams().getTeamTasks(), redis.getGroups().getTeamTasks()));
            setAvailability("redis", true, null);
        } catch (RuntimeException e) {
            setAvailability("redis", false, e);
        }
    }

    private void refreshDatabaseSafely() {
        try {
            set(recordPending, "outbox", teamEventOutboxRepository.countByStatus(TeamEventOutboxEntity.STATUS_PENDING));
            set(recordRetries, "outbox", teamEventOutboxRepository.countByStatusAndRetryCountGreaterThan(
                    TeamEventOutboxEntity.STATUS_PENDING, 0));
            set(recordPending, "team-tasks", teamTaskRepository.countByStatus(TeamTaskEntity.STATUS_PENDING));
            set(recordRetries, "team-tasks", teamTaskRepository.countByStatusAndRetryCountGreaterThan(
                    TeamTaskEntity.STATUS_PENDING, 0));
            setAvailability("database", true, null);
        } catch (RuntimeException e) {
            setAvailability("database", false, e);
        }
    }

    private void warnOnBacklog() {
        long backlogThreshold = queueProperties.getRedis().getBacklogWarnThreshold();
        consumerPending.forEach((stream, value) -> updateWarning(
                "pending:" + stream, "Redis consumer pending", stream, value.get(), backlogThreshold));
        recordPending.forEach((source, value) -> updateWarning(
                "records-pending:" + source, "Durable queue pending", source, value.get(), backlogThreshold));
        recordRetries.forEach((source, value) -> updateWarning(
                "records-retry:" + source, "Durable queue retry", source, value.get(), backlogThreshold));
        updateWarning(
                "dead-letter", "Dead-letter queue", "dead-letter",
                streamLengths.get("dead-letter").get(), queueProperties.getRedis().getDeadLetterWarnThreshold());
    }

    private void updateWarning(String key, String type, String source, long value, long threshold) {
        boolean abnormal = threshold > 0 && value >= threshold;
        AtomicBoolean previous = warningStates.computeIfAbsent(key, ignored -> new AtomicBoolean());
        if (abnormal && previous.compareAndSet(false, true)) {
            log.warn("Queue backlog threshold reached: type={}, source={}, value={}, threshold={}",
                    type, source, value, threshold);
        } else if (!abnormal && previous.compareAndSet(true, false)) {
            log.info("Queue backlog recovered: type={}, source={}, value={}, threshold={}",
                    type, source, value, threshold);
        }
    }

    private void setAvailability(String source, boolean available, RuntimeException error) {
        observationAvailability.get(source).set(available ? 1L : 0L);
        String key = "observation:" + source;
        AtomicBoolean failed = warningStates.computeIfAbsent(key, ignored -> new AtomicBoolean());
        if (!available && failed.compareAndSet(false, true)) {
            log.warn("Queue metrics source unavailable: source={}", source, error);
        } else if (available && failed.compareAndSet(true, false)) {
            log.info("Queue metrics source recovered: source={}", source);
        }
    }

    private QueueSnapshot snapshot() {
        return new QueueSnapshot(
                sum(streamLengths),
                sum(consumerPending),
                sum(recordPending),
                sum(recordRetries),
                streamLengths.get("dead-letter").get());
    }

    private long sum(Map<String, AtomicLong> values) {
        return values.values().stream().mapToLong(AtomicLong::get).sum();
    }

    private void set(Map<String, AtomicLong> values, String key, long value) {
        values.get(key).set(Math.max(value, 0L));
    }

    private String streamLabel(String stream) {
        QueueProperties.Streams streams = queueProperties.getRedis().getStreams();
        if (streams.getTeamEvents().equals(stream)) {
            return "team-events";
        }
        if (streams.getNotifications().equals(stream)) {
            return "notifications";
        }
        if (streams.getTeamTasks().equals(stream)) {
            return "team-tasks";
        }
        return "unknown";
    }

    private String normalize(String value, List<String> knownValues) {
        if (value == null) {
            return "unknown";
        }
        String normalized = value.trim().toLowerCase();
        return knownValues.contains(normalized) ? normalized : "unknown";
    }

    public record QueueSnapshot(
            long streamMessages,
            long consumerPending,
            long databasePending,
            long retries,
            long deadLetters) {
    }
}
