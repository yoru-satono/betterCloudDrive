package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.DeadLetterRecordEntity;
import com.betterclouddrive.dal.repository.DeadLetterRecordRepository;
import com.betterclouddrive.dal.repository.TeamEventOutboxRepository;
import com.betterclouddrive.service.OperationLogService;
import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.metrics.DeadLetterMetrics;
import com.betterclouddrive.service.queue.RabbitQueueService;
import org.junit.jupiter.api.*;
import org.mockito.*;
import tools.jackson.databind.ObjectMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionDefinition;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeadLetterServiceImplTest {
    @Mock DeadLetterRecordRepository repository;
    @Mock RabbitQueueService rabbit;
    @Mock OperationLogService audit;
    @Mock DeadLetterMetrics metrics;
    @Mock TeamEventOutboxRepository outbox;
    @Mock ObjectMapper objectMapper;
    @Mock PlatformTransactionManager transactionManager;
    DeadLetterServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(mock(TransactionStatus.class));
        service = new DeadLetterServiceImpl(repository, rabbit, new QueueProperties(), audit, metrics,
                outbox, objectMapper, transactionManager);
    }

    @Test
    void replayIsSingleUseAndAudited() {
        UUID id = UUID.randomUUID();
        DeadLetterRecordEntity record = record(id, DeadLetterRecordEntity.STATUS_OPEN);
        record.setOriginalQueue("team-tasks");
        record.setRoutingKey("team-task.wakeup");
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(record), Optional.of(record), Optional.of(record));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var replayed = service.replay(7L, List.of(id));

        assertThat(replayed).singleElement().extracting(view -> view.status()).isEqualTo("REPLAYED");
        verify(rabbit).republish(eq("team-task.wakeup"), anyString(), eq("{\"outboxId\":\"1\",\"targetType\":\"USER\"}"),
                isNull(), isNull(), anyString());
        verify(audit).logSuccess(eq(7L), eq("DEAD_LETTER_REPLAY"), eq("DEAD_LETTER"), isNull(), contains(id.toString()));

        record.setStatus(DeadLetterRecordEntity.STATUS_REPLAYED);
        assertThatThrownBy(() -> service.replay(7L, List.of(id))).isInstanceOf(BusinessException.class);
        verify(rabbit, times(1)).republish(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void replayRejectsOversizedOrDuplicateBatch() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.replay(1L, List.of(id, id))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.replay(1L,
                java.util.stream.IntStream.range(0, 21).mapToObj(ignored -> UUID.randomUUID()).toList()))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(rabbit);
    }

    @Test
    void discardRequiresReasonAndIsAudited() {
        UUID id = UUID.randomUUID();
        DeadLetterRecordEntity record = record(id, DeadLetterRecordEntity.STATUS_OPEN);
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.discard(9L, id, " ")).isInstanceOf(BusinessException.class);
        var discarded = service.discard(9L, id, "invalid fixture");

        assertThat(discarded.status()).isEqualTo("DISCARDED");
        assertThat(record.getResolutionReason()).isEqualTo("invalid fixture");
        verify(audit).logSuccess(eq(9L), eq("DEAD_LETTER_DISCARD"), eq("DEAD_LETTER"), isNull(), contains(id.toString()));
    }

    private DeadLetterRecordEntity record(UUID id, String status) {
        LocalDateTime now = LocalDateTime.now();
        return DeadLetterRecordEntity.builder()
                .id(id).originalMessageId("message-1").originalQueue("notifications")
                .routingKey("notification.dispatch").taskType("USER")
                .failureCategory("delivery-exhausted").deliveryCount(5)
                .firstFailedAt(now).lastFailedAt(now)
                .payload("{\"outboxId\":\"1\",\"targetType\":\"USER\"}")
                .status(status).createdAt(now).updatedAt(now).build();
    }
}
