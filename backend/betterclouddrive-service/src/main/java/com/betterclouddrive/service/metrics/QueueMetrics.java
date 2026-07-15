package com.betterclouddrive.service.metrics;

import com.betterclouddrive.dal.entity.TeamEventOutboxEntity;
import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.dal.repository.TeamEventOutboxRepository;
import com.betterclouddrive.dal.repository.TeamTaskRepository;
import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.queue.RabbitQueueService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class QueueMetrics {
    private static final String PREFIX = "bcd.queue.";
    private static final List<String> OPERATIONS = List.of("outbox-publish", "notification-dispatch", "team-task-dispatch");
    private static final List<String> OUTCOMES = List.of("success", "failure");
    private final MeterRegistry registry;
    private final RabbitQueueService rabbit;
    private final TeamEventOutboxRepository outbox;
    private final TeamTaskRepository tasks;
    private final QueueProperties properties;
    private final Map<String, AtomicLong> ready = new LinkedHashMap<>();
    private final Map<String, AtomicLong> inflight = new LinkedHashMap<>();
    private final Map<String, AtomicLong> pending = new LinkedHashMap<>();
    private final Map<String, AtomicLong> retries = new LinkedHashMap<>();
    private final Map<String, AtomicLong> availability = new LinkedHashMap<>();
    private final Map<String, AtomicBoolean> warnings = new ConcurrentHashMap<>();

    public QueueMetrics(MeterRegistry registry, RabbitQueueService rabbit,
                        TeamEventOutboxRepository outbox, TeamTaskRepository tasks, QueueProperties properties) {
        this.registry = registry; this.rabbit = rabbit; this.outbox = outbox; this.tasks = tasks; this.properties = properties;
        for (String queue : List.of("notifications", "team-tasks", "dead-letter")) {
            ready.put(queue, gauge("messages.ready", "queue", queue, "Ready messages in RabbitMQ"));
        }
        for (String queue : List.of("notifications", "team-tasks")) {
            inflight.put(queue, gauge("consumer.inflight", "queue", queue, "Messages currently handled by this application"));
        }
        for (String source : List.of("outbox", "team-tasks")) {
            pending.put(source, gauge("records.pending", "source", source, "Pending durable queue records"));
            retries.put(source, gauge("records.retry", "source", source, "Retried durable queue records"));
        }
        for (String source : List.of("rabbitmq", "database")) {
            availability.put(source, gauge("observation.available", "source", source, "Queue metric source availability"));
        }
        for (String queue : List.of("notifications", "team-tasks", "unknown")) registry.counter(PREFIX + "deadlettered", "queue", queue);
        for (String operation : OPERATIONS) for (String outcome : OUTCOMES) {
            registry.counter(PREFIX + "processing.results", "operation", operation, "outcome", outcome);
            registry.timer(PREFIX + "processing.duration", "operation", operation, "outcome", outcome);
        }
    }

    public QueueSnapshot refresh() {
        refreshRabbit(); refreshDatabase(); warnOnBacklog();
        return new QueueSnapshot(sum(ready), sum(inflight), sum(pending), sum(retries), ready.get("dead-letter").get());
    }

    public long startProcessing() { return System.nanoTime(); }

    public void recordProcessing(String operation, String outcome, long startedAt) {
        operation = normalize(operation, OPERATIONS); outcome = normalize(outcome, OUTCOMES);
        registry.counter(PREFIX + "processing.results", "operation", operation, "outcome", outcome).increment();
        registry.timer(PREFIX + "processing.duration", "operation", operation, "outcome", outcome)
                .record(Math.max(0L, System.nanoTime() - startedAt), TimeUnit.NANOSECONDS);
    }

    public void beginConsumer(String queue) { value(inflight, queue).incrementAndGet(); }
    public void endConsumer(String queue) { value(inflight, queue).updateAndGet(current -> Math.max(0, current - 1)); }
    public void recordDeadLetter(String queue) { registry.counter(PREFIX + "deadlettered", "queue", knownQueue(queue)).increment(); }
    public void recordTaskClaim(String type, int claimed) { registry.counter(PREFIX + "team_tasks.claimed", "type", type).increment(Math.max(0, claimed)); }
    public void recordDuplicateTaskWakeup(String type) { registry.counter(PREFIX + "team_tasks.duplicate_wakeups", "type", type == null ? "unknown" : type).increment(); }
    public void recordTimedOutTaskRecovery(int recovered) { registry.counter(PREFIX + "team_tasks.timeout_recovered").increment(Math.max(0, recovered)); }

    private void refreshRabbit() {
        if (!properties.isEnabled()) { ready.values().forEach(v -> v.set(0)); available("rabbitmq", true, null); return; }
        try {
            var rabbitmq = properties.getRabbitmq();
            ready.get("notifications").set(rabbit.messageCount(rabbitmq.getNotificationsQueue()));
            ready.get("team-tasks").set(rabbit.messageCount(rabbitmq.getTeamTasksQueue()));
            ready.get("dead-letter").set(rabbit.messageCount(rabbitmq.getDeadLetterQueue()));
            available("rabbitmq", true, null);
        } catch (RuntimeException e) { available("rabbitmq", false, e); }
    }

    private void refreshDatabase() {
        try {
            pending.get("outbox").set(outbox.countByStatus(TeamEventOutboxEntity.STATUS_PENDING));
            retries.get("outbox").set(outbox.countByStatusAndRetryCountGreaterThan(TeamEventOutboxEntity.STATUS_PENDING, 0));
            pending.get("team-tasks").set(tasks.countByStatus(TeamTaskEntity.STATUS_PENDING));
            retries.get("team-tasks").set(tasks.countByStatusAndRetryCountGreaterThan(TeamTaskEntity.STATUS_PENDING, 0));
            available("database", true, null);
        } catch (RuntimeException e) { available("database", false, e); }
    }

    private void warnOnBacklog() {
        long threshold = properties.getRabbitmq().getBacklogWarnThreshold();
        ready.forEach((queue, value) -> warning("ready:" + queue, "RabbitMQ ready", queue, value.get(),
                queue.equals("dead-letter") ? properties.getRabbitmq().getDeadLetterWarnThreshold() : threshold));
        pending.forEach((source, value) -> warning("pending:" + source, "Durable queue pending", source, value.get(), threshold));
        retries.forEach((source, value) -> warning("retry:" + source, "Durable queue retry", source, value.get(), threshold));
    }

    private void warning(String key, String type, String source, long count, long threshold) {
        boolean bad = threshold > 0 && count >= threshold;
        AtomicBoolean state = warnings.computeIfAbsent(key, ignored -> new AtomicBoolean());
        if (bad && state.compareAndSet(false, true)) log.warn("Queue backlog threshold reached: type={}, source={}, value={}, threshold={}", type, source, count, threshold);
        else if (!bad && state.compareAndSet(true, false)) log.info("Queue backlog recovered: type={}, source={}, value={}, threshold={}", type, source, count, threshold);
    }

    private void available(String source, boolean available, RuntimeException error) {
        availability.get(source).set(available ? 1 : 0);
        AtomicBoolean failed = warnings.computeIfAbsent("availability:" + source, ignored -> new AtomicBoolean());
        if (!available && failed.compareAndSet(false, true)) log.warn("Queue metrics source unavailable: source={}", source, error);
        else if (available && failed.compareAndSet(true, false)) log.info("Queue metrics source recovered: source={}", source);
    }

    private AtomicLong gauge(String name, String tag, String label, String description) {
        AtomicLong value = new AtomicLong();
        Gauge.builder(PREFIX + name, value, AtomicLong::get).tag(tag, label).description(description).register(registry);
        return value;
    }
    private AtomicLong value(Map<String, AtomicLong> values, String key) { return values.getOrDefault(key, values.values().iterator().next()); }
    private long sum(Map<String, AtomicLong> values) { return values.values().stream().mapToLong(AtomicLong::get).sum(); }
    private String knownQueue(String value) { return List.of("notifications", "team-tasks").contains(value) ? value : "unknown"; }
    private String normalize(String value, List<String> allowed) { String v = value == null ? "unknown" : value.trim().toLowerCase(); return allowed.contains(v) ? v : "unknown"; }

    public record QueueSnapshot(long streamMessages, long consumerPending, long databasePending, long retries, long deadLetters) {}
}
