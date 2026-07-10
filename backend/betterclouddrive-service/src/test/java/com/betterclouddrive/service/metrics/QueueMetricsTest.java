package com.betterclouddrive.service.metrics;

import com.betterclouddrive.dal.entity.TeamEventOutboxEntity;
import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.dal.repository.TeamEventOutboxRepository;
import com.betterclouddrive.dal.repository.TeamTaskRepository;
import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.queue.RedisStreamQueueService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueMetricsTest {

    @Mock
    private RedisStreamQueueService redisStreamQueueService;

    @Mock
    private TeamEventOutboxRepository teamEventOutboxRepository;

    @Mock
    private TeamTaskRepository teamTaskRepository;

    private SimpleMeterRegistry meterRegistry;
    private QueueProperties queueProperties;
    private QueueMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        queueProperties = new QueueProperties();
        metrics = new QueueMetrics(
                meterRegistry,
                redisStreamQueueService,
                teamEventOutboxRepository,
                teamTaskRepository,
                queueProperties);
    }

    @Test
    void refresh_shouldExposeRedisAndDatabaseBacklog() {
        QueueProperties.Redis redis = queueProperties.getRedis();
        when(redisStreamQueueService.streamLength(redis.getStreams().getTeamEvents())).thenReturn(5L);
        when(redisStreamQueueService.streamLength(redis.getStreams().getNotifications())).thenReturn(6L);
        when(redisStreamQueueService.streamLength(redis.getStreams().getTeamTasks())).thenReturn(7L);
        when(redisStreamQueueService.streamLength(redis.getStreams().getDeadLetter())).thenReturn(2L);
        when(redisStreamQueueService.pendingCount(redis.getStreams().getTeamEvents(), redis.getGroups().getTeamEvents()))
                .thenReturn(1L);
        when(redisStreamQueueService.pendingCount(redis.getStreams().getNotifications(), redis.getGroups().getNotifications()))
                .thenReturn(2L);
        when(redisStreamQueueService.pendingCount(redis.getStreams().getTeamTasks(), redis.getGroups().getTeamTasks()))
                .thenReturn(3L);
        when(teamEventOutboxRepository.countByStatus(TeamEventOutboxEntity.STATUS_PENDING)).thenReturn(4L);
        when(teamEventOutboxRepository.countByStatusAndRetryCountGreaterThan(TeamEventOutboxEntity.STATUS_PENDING, 0))
                .thenReturn(2L);
        when(teamTaskRepository.countByStatus(TeamTaskEntity.STATUS_PENDING)).thenReturn(8L);
        when(teamTaskRepository.countByStatusAndRetryCountGreaterThan(TeamTaskEntity.STATUS_PENDING, 0))
                .thenReturn(3L);

        QueueMetrics.QueueSnapshot snapshot = metrics.refresh();

        assertThat(snapshot).isEqualTo(new QueueMetrics.QueueSnapshot(20L, 6L, 12L, 5L, 2L));
        assertThat(gauge("bcd.queue.stream.length", "stream", "notifications")).isEqualTo(6.0);
        assertThat(gauge("bcd.queue.consumer.pending", "stream", "team-tasks")).isEqualTo(3.0);
        assertThat(gauge("bcd.queue.records.pending", "source", "outbox")).isEqualTo(4.0);
        assertThat(gauge("bcd.queue.records.retry", "source", "team-tasks")).isEqualTo(3.0);
        assertThat(gauge("bcd.queue.observation.available", "source", "redis")).isEqualTo(1.0);
        assertThat(gauge("bcd.queue.observation.available", "source", "database")).isEqualTo(1.0);
    }

    @Test
    void refresh_shouldMarkRedisUnavailableWithoutLosingDatabaseMetrics() {
        when(redisStreamQueueService.streamLength(queueProperties.getRedis().getStreams().getTeamEvents()))
                .thenThrow(new IllegalStateException("redis down"));
        when(teamEventOutboxRepository.countByStatus(TeamEventOutboxEntity.STATUS_PENDING)).thenReturn(4L);

        QueueMetrics.QueueSnapshot snapshot = metrics.refresh();

        assertThat(snapshot.databasePending()).isEqualTo(4L);
        assertThat(gauge("bcd.queue.observation.available", "source", "redis")).isZero();
        assertThat(gauge("bcd.queue.observation.available", "source", "database")).isEqualTo(1.0);
    }

    @Test
    void recordProcessingAndDeadLetter_shouldIncrementCountersAndTimer() {
        long startedAt = metrics.startProcessing();

        metrics.recordProcessing("notification-dispatch", "success", startedAt);
        metrics.recordDeadLetter(queueProperties.getRedis().getStreams().getNotifications());

        assertThat(meterRegistry.get("bcd.queue.processing.results")
                .tags("operation", "notification-dispatch", "outcome", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bcd.queue.processing.duration")
                .tags("operation", "notification-dispatch", "outcome", "success")
                .timer().count()).isEqualTo(1L);
        assertThat(meterRegistry.get("bcd.queue.deadlettered")
                .tag("stream", "notifications").counter().count()).isEqualTo(1.0);
    }

    private double gauge(String name, String tagName, String tagValue) {
        return meterRegistry.get(name).tag(tagName, tagValue).gauge().value();
    }
}
