package com.betterclouddrive.service.queue;

import com.betterclouddrive.service.config.QueueProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisStreamQueueServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    @SuppressWarnings("rawtypes")
    private StreamOperations streamOperations;

    private QueueProperties queueProperties;
    private RedisStreamQueueService service;

    @BeforeEach
    void setUp() {
        queueProperties = new QueueProperties();
        service = new RedisStreamQueueService(redisTemplate, queueProperties);
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void publish_shouldAddRecordAndTrimStream() {
        useStreamOperations();
        when(streamOperations.add(any(MapRecord.class))).thenReturn(RecordId.of("1-0"));

        String recordId = service.publish("bcd:stream:team-events", Map.of("eventType", "TEAM_CREATED"));

        assertThat(recordId).isEqualTo("1-0");
        ArgumentCaptor<MapRecord> recordCaptor = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOperations).add(recordCaptor.capture());
        Map<?, ?> payload = (Map<?, ?>) recordCaptor.getValue().getValue();
        assertThat(recordCaptor.getValue().getStream()).isEqualTo("bcd:stream:team-events");
        assertThat(payload.get("eventType")).isEqualTo("TEAM_CREATED");
        assertThat(payload.get("createdAt")).isNotNull();
        verify(streamOperations).trim("bcd:stream:team-events", 10000L, true);
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void publish_shouldSkipRedisWhenQueueDisabled() {
        queueProperties.setEnabled(false);

        String recordId = service.publish("bcd:stream:team-events", Map.of("eventType", "TEAM_CREATED"));

        assertThat(recordId).isEmpty();
        verify(streamOperations, never()).add(any(MapRecord.class));
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void ensureConsumerGroup_shouldCreateMissingStreamWithInitRecord() {
        useStreamOperations();
        RedisSystemException missingStream = new RedisSystemException("ERR The XGROUP subcommand requires the key to exist", null);
        when(streamOperations.createGroup(eq("bcd:stream:team-events"), any(), eq("workers")))
                .thenThrow(missingStream)
                .thenReturn("OK");
        when(streamOperations.add(any(MapRecord.class))).thenReturn(RecordId.of("0-1"));

        service.ensureConsumerGroup("bcd:stream:team-events", "workers");

        verify(streamOperations).delete("bcd:stream:team-events", "0-1");
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void readGroup_shouldMapRecordsAndAcknowledge() {
        useStreamOperations();
        MapRecord record = org.mockito.Mockito.mock(MapRecord.class);
        when(record.getStream()).thenReturn("bcd:stream:team-events");
        when(record.getId()).thenReturn(RecordId.of("2-0"));
        when(record.getValue()).thenReturn(Map.of("eventType", "TEAM_CREATED", "teamId", "10"));
        when(streamOperations.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(record));
        when(streamOperations.acknowledge("bcd:stream:team-events", "workers", "2-0")).thenReturn(1L);

        List<QueueMessage> messages = service.readGroup(
                "bcd:stream:team-events",
                "workers",
                "consumer-1",
                10,
                Duration.ofMillis(50));
        long acknowledged = service.acknowledge("bcd:stream:team-events", "workers", "2-0");

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().id()).isEqualTo("2-0");
        assertThat(messages.getFirst().body()).containsEntry("eventType", "TEAM_CREATED");
        assertThat(messages.getFirst().body()).containsEntry("teamId", "10");
        assertThat(acknowledged).isEqualTo(1L);
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void moveToDeadLetter_shouldPublishOriginalMessageMetadata() {
        useStreamOperations();
        when(streamOperations.add(any(MapRecord.class))).thenReturn(RecordId.of("3-0"));
        QueueMessage message = new QueueMessage(
                "bcd:stream:team-events",
                "2-0",
                Map.of("eventType", "TEAM_CREATED", "teamId", "10"));

        String deadLetterId = service.moveToDeadLetter(message, "boom");

        assertThat(deadLetterId).isEqualTo("3-0");
        ArgumentCaptor<MapRecord> recordCaptor = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOperations).add(recordCaptor.capture());
        Map<?, ?> payload = (Map<?, ?>) recordCaptor.getValue().getValue();
        assertThat(recordCaptor.getValue().getStream()).isEqualTo("bcd:stream:dead-letter");
        assertThat(payload.get("originalStream")).isEqualTo("bcd:stream:team-events");
        assertThat(payload.get("originalId")).isEqualTo("2-0");
        assertThat(payload.get("reason")).isEqualTo("boom");
        assertThat(payload.get("eventType")).isEqualTo("TEAM_CREATED");
    }

    @SuppressWarnings("unchecked")
    private void useStreamOperations() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
    }
}
