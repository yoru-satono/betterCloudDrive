package com.betterclouddrive.service.queue;

import com.betterclouddrive.dal.entity.TeamEventOutboxEntity;
import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.dal.repository.TeamEventOutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TeamTaskOutboxWriterTest {
    @Mock TeamEventOutboxRepository repository;

    @Test void storesDurableWakeupWithTaskIdentityAndType() {
        var writer = new TeamTaskOutboxWriter(
                repository,
                new com.betterclouddrive.service.observability.AsyncTraceService(
                        io.opentelemetry.api.OpenTelemetry.noop()));
        writer.enqueue(TeamTaskEntity.builder().id(77L).teamId(10L).actorUserId(2L).taskType("AUDIT_EXPORT").build());
        var captor = ArgumentCaptor.forClass(TeamEventOutboxEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue()).satisfies(event -> {
            assertThat(event.getAggregateType()).isEqualTo("TEAM_TASK");
            assertThat(event.getAggregateId()).isEqualTo(77L);
            assertThat(event.getTargetType()).isEqualTo("AUDIT_EXPORT");
            assertThat(event.getStatus()).isEqualTo(TeamEventOutboxEntity.STATUS_PENDING);
        });
    }
}
