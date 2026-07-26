package com.betterclouddrive.service.queue;

import com.betterclouddrive.dal.entity.TeamEventOutboxEntity;
import com.betterclouddrive.dal.repository.TeamEventOutboxRepository;
import com.betterclouddrive.service.NotificationService;
import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.metrics.QueueMetrics;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamNotificationDispatchServiceImplTest {
    @Mock TeamNotificationOutboxWriter writer; @Mock TeamEventOutboxRepository repository; @Mock RabbitQueueService rabbit;
    @Mock NotificationService notifications; @Mock QueueMetrics metrics; @Mock Channel channel;
    QueueProperties properties; TeamNotificationDispatchServiceImpl service;
    @BeforeEach void setUp() {
        properties = new QueueProperties();
        service = new TeamNotificationDispatchServiceImpl(
                writer, repository, rabbit, notifications, properties, new ObjectMapper(), metrics,
                new com.betterclouddrive.service.observability.AsyncTraceService(
                        io.opentelemetry.api.OpenTelemetry.noop()));
    }

    @Test void dispatchesNotificationMarksOutboxProcessedAndAcks() throws Exception {
        var event = processing(1L, 0); when(repository.findById(1L)).thenReturn(java.util.Optional.of(event));
        when(rabbit.read(any())).thenReturn(Map.of("outboxId", "1", "targetType", "NOTIFICATION_USER", "payload", payload()));
        service.processQueuedNotification(message(), channel);
        verify(notifications).notifyUser(eq(2L), eq(3L), eq("TYPE"), anyString(), anyString(), anyMap(), isNull(), eq(false));
        verify(channel).basicAck(9L, false); verify(repository).save(event);
    }

    @Test void transientFailureReturnsOutboxToPendingAndAcksForRepublish() throws Exception {
        var event = processing(1L, 0); when(repository.findById(1L)).thenReturn(java.util.Optional.of(event));
        when(rabbit.read(any())).thenReturn(Map.of("outboxId", "1", "targetType", "NOTIFICATION_USER", "payload", payload()));
        doThrow(new IllegalStateException("mail down")).when(notifications).notifyUser(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyMap(), any(), anyBoolean());
        service.processQueuedNotification(message(), channel);
        verify(channel).basicAck(9L, false); org.assertj.core.api.Assertions.assertThat(event.getStatus()).isEqualTo("PENDING");
    }

    @Test void missingOutboxIsRejectedToDeadLetter() throws Exception {
        when(rabbit.read(any())).thenReturn(Map.of("outboxId", "404")); when(repository.findById(404L)).thenReturn(java.util.Optional.empty());
        service.processQueuedNotification(message(), channel);
        verify(channel).basicReject(9L, false); verify(metrics).recordDeadLetter("notifications");
    }

    @Test void finalFailureMarksOutboxFailedAndRejectsToDeadLetter() throws Exception {
        var event = processing(1L, 4); when(repository.findById(1L)).thenReturn(java.util.Optional.of(event));
        when(rabbit.read(any())).thenReturn(Map.of("outboxId", "1", "targetType", "NOTIFICATION_USER", "payload", payload()));
        doThrow(new IllegalStateException("mail down")).when(notifications).notifyUser(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyMap(), any(), anyBoolean());
        service.processQueuedNotification(message(), channel);
        verify(channel).basicReject(9L, false); verify(metrics).recordDeadLetter("notifications");
        org.assertj.core.api.Assertions.assertThat(event.getStatus()).isEqualTo("FAILED");
    }

    private TeamEventOutboxEntity processing(Long id, int retries) { return TeamEventOutboxEntity.builder().id(id).status("PROCESSING").retryCount(retries).updatedAt(LocalDateTime.now()).build(); }
    private String payload() { return "{\"userId\":2,\"teamId\":3,\"type\":\"TYPE\",\"titleKey\":\"title\",\"contentKey\":\"content\",\"messageParams\":{},\"email\":false}"; }
    private Message message() { var p = new MessageProperties(); p.setDeliveryTag(9L); p.setMessageId("id"); return new Message(new byte[0], p); }
}
