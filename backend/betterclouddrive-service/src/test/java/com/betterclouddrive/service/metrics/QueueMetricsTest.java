package com.betterclouddrive.service.metrics;

import com.betterclouddrive.dal.repository.TeamEventOutboxRepository;
import com.betterclouddrive.dal.repository.TeamTaskRepository;
import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.queue.RabbitQueueService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class QueueMetricsTest {
    @Test void refreshesRabbitAndDatabaseMetrics() {
        var registry = new SimpleMeterRegistry(); var rabbit = mock(RabbitQueueService.class);
        var outbox = mock(TeamEventOutboxRepository.class); var tasks = mock(TeamTaskRepository.class); var props = new QueueProperties();
        when(rabbit.messageCount("bcd.notifications")).thenReturn(3L); when(rabbit.messageCount("bcd.team-tasks")).thenReturn(4L); when(rabbit.messageCount("bcd.dead-letter")).thenReturn(1L);
        when(outbox.countByStatus("PENDING")).thenReturn(2L); when(tasks.countByStatus("PENDING")).thenReturn(5L);
        var observation = new ObservationMetrics(registry);
        var metrics = new QueueMetrics(registry, rabbit, outbox, tasks, props, observation);
        assertThat(metrics.refresh()).isEqualTo(new QueueMetrics.QueueSnapshot(8, 0, 7, 0, 1));
        assertThat(registry.get("bcd.queue.messages.ready").tag("queue", "notifications").gauge().value()).isEqualTo(3);
        assertThat(registry.get("bcd.queue.observation.available").tag("source", "rabbitmq").gauge().value()).isEqualTo(1);
        assertThat(registry.get("bcd.observation.last.success.timestamp").tag("source", "queue-rabbitmq").gauge().value()).isPositive();
        assertThat(registry.get("bcd.observation.last.success.timestamp").tag("source", "queue-database").gauge().value()).isPositive();
    }

    @Test void recordsSourceFailureWithoutDiscardingTheOtherSource() {
        var registry = new SimpleMeterRegistry(); var rabbit = mock(RabbitQueueService.class);
        var outbox = mock(TeamEventOutboxRepository.class); var tasks = mock(TeamTaskRepository.class); var props = new QueueProperties();
        when(rabbit.messageCount("bcd.notifications")).thenThrow(new IllegalStateException("rabbit unavailable"));
        when(outbox.countByStatus("PENDING")).thenReturn(2L); when(tasks.countByStatus("PENDING")).thenReturn(5L);
        var metrics = new QueueMetrics(registry, rabbit, outbox, tasks, props, new ObservationMetrics(registry));

        metrics.refresh();

        assertThat(registry.get("bcd.observation.refresh.failures").tag("source", "queue-rabbitmq").counter().count()).isEqualTo(1);
        assertThat(registry.get("bcd.observation.last.success.timestamp").tag("source", "queue-rabbitmq").gauge().value()).isZero();
        assertThat(registry.get("bcd.observation.last.success.timestamp").tag("source", "queue-database").gauge().value()).isPositive();
    }
}
