package com.betterclouddrive.service.queue;

import com.betterclouddrive.dal.entity.TeamEventOutboxEntity;
import com.betterclouddrive.dal.repository.TeamEventOutboxRepository;
import com.betterclouddrive.service.config.QueueProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamEventOutboxQueuePublisherTest {
    @Mock TeamEventOutboxRepository repository;
    @Mock RabbitQueueService rabbit;
    QueueProperties properties;
    TeamEventOutboxQueuePublisher publisher;

    @BeforeEach void setUp() { properties = new QueueProperties(); publisher = new TeamEventOutboxQueuePublisher(repository, rabbit, properties); }

    @Test void ordinaryTeamEventRemainsInOutboxWithoutRabbitQueue() {
        var event = event("TEAM", "TEAM_CREATED"); pending(event);
        assertThat(publisher.publishPending(10)).isEqualTo(1);
        verifyNoInteractions(rabbit);
        assertThat(event.getStatus()).isEqualTo(TeamEventOutboxEntity.STATUS_PROCESSED);
    }

    @Test void notificationIsPublishedAndRemainsProcessingUntilConsumerAck() {
        var event = event("NOTIFICATION", "TEAM_INVITE"); pending(event);
        when(rabbit.publish(eq("notification.dispatch"), any(Map.class))).thenReturn("message-id");
        assertThat(publisher.publishPending(10)).isEqualTo(1);
        verify(rabbit).publish(eq("notification.dispatch"), any(Map.class));
        assertThat(event.getStatus()).isEqualTo(TeamEventOutboxEntity.STATUS_PROCESSING);
    }

    @Test void teamTaskIsPublishedAndMarkedProcessedAfterConfirm() {
        var event = event(TeamTaskOutboxWriter.AGGREGATE_TEAM_TASK, TeamTaskOutboxWriter.EVENT_TASK_READY); pending(event);
        when(rabbit.publish(eq("team-task.wakeup"), any(Map.class))).thenReturn("message-id");
        assertThat(publisher.publishPending(10)).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(TeamEventOutboxEntity.STATUS_PROCESSED);
    }

    @Test void publishFailureReturnsOutboxToPending() {
        var event = event("NOTIFICATION", "TEAM_INVITE"); pending(event);
        when(rabbit.publish(anyString(), any(Map.class))).thenThrow(new IllegalStateException("rabbit down"));
        assertThat(publisher.publishPending(10)).isZero();
        assertThat(event.getStatus()).isEqualTo(TeamEventOutboxEntity.STATUS_PENDING);
        assertThat(event.getRetryCount()).isEqualTo(1);
    }

    private void pending(TeamEventOutboxEntity event) {
        when(repository.findByStatusAndAvailableAtLessThanEqualOrderByIdAsc(eq("PENDING"), any(), any(Pageable.class))).thenReturn(List.of(event));
    }
    private TeamEventOutboxEntity event(String aggregateType, String eventType) {
        var now = LocalDateTime.now();
        return TeamEventOutboxEntity.builder().id(99L).aggregateType(aggregateType).aggregateId(10L).eventType(eventType)
                .targetType("AUDIT_EXPORT").targetId(42L).payload("{}").status("PENDING").retryCount(0)
                .availableAt(now.minusSeconds(1)).createdAt(now).updatedAt(now).build();
    }
}
