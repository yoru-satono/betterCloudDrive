package com.betterclouddrive.service.queue;

import com.betterclouddrive.service.config.QueueProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@SuppressWarnings({ "unchecked", "rawtypes" })
public class RedisStreamQueueService {

    private static final String INIT_EVENT_TYPE = "__QUEUE_INIT__";

    private final StringRedisTemplate redisTemplate;
    private final QueueProperties queueProperties;

    public boolean isEnabled() {
        return queueProperties.isEnabled();
    }

    public String publish(String stream, Map<String, String> fields) {
        if (!isEnabled() || !StringUtils.hasText(stream)) {
            return "";
        }
        Map<String, String> payload = normalizePayload(fields);
        payload.putIfAbsent("createdAt", Instant.now().toString());
        RecordId recordId = redisTemplate.opsForStream()
                .add(StreamRecords.string(payload).withStreamKey(stream));
        trim(stream);
        return recordId == null ? "" : recordId.getValue();
    }

    public void ensureConsumerGroup(String stream, String group) {
        if (!isEnabled() || !StringUtils.hasText(stream) || !StringUtils.hasText(group)) {
            return;
        }
        try {
            createGroup(stream, group);
        } catch (RedisSystemException e) {
            if (isBusyGroup(e)) {
                return;
            }
            if (!isMissingStream(e)) {
                throw e;
            }
            RecordId initRecord = redisTemplate.opsForStream()
                    .add(StreamRecords.string(Map.of("eventType", INIT_EVENT_TYPE, "createdAt", Instant.now().toString()))
                            .withStreamKey(stream));
            try {
                createGroup(stream, group);
            } catch (RedisSystemException retryError) {
                if (!isBusyGroup(retryError)) {
                    throw retryError;
                }
            } finally {
                if (initRecord != null) {
                    redisTemplate.opsForStream().delete(stream, initRecord.getValue());
                }
            }
        }
    }

    public List<QueueMessage> readGroup(String stream, String group, String consumer, int count, Duration blockTimeout) {
        if (!isEnabled() || !StringUtils.hasText(stream) || !StringUtils.hasText(group) || !StringUtils.hasText(consumer)) {
            return List.of();
        }
        int batchSize = count > 0 ? count : queueProperties.getRedis().getReadBatchSize();
        Duration block = blockTimeout != null ? blockTimeout : Duration.ofMillis(queueProperties.getRedis().getBlockTimeoutMs());
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(batchSize).block(block),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .map(this::toQueueMessage)
                .filter(message -> !INIT_EVENT_TYPE.equals(message.body().get("eventType")))
                .toList();
    }

    public long acknowledge(String stream, String group, String recordId) {
        if (!isEnabled() || !StringUtils.hasText(stream) || !StringUtils.hasText(group) || !StringUtils.hasText(recordId)) {
            return 0L;
        }
        Long acknowledged = redisTemplate.opsForStream().acknowledge(stream, group, recordId);
        return acknowledged == null ? 0L : acknowledged;
    }

    public String moveToDeadLetter(QueueMessage message, String reason) {
        if (message == null) {
            return "";
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("originalStream", message.stream());
        payload.put("originalId", message.id());
        payload.put("reason", StringUtils.hasText(reason) ? reason : "unknown");
        payload.put("failedAt", Instant.now().toString());
        payload.putAll(message.body());
        return publish(queueProperties.getRedis().getStreams().getDeadLetter(), payload);
    }

    public long streamLength(String stream) {
        if (!isEnabled() || !StringUtils.hasText(stream)) {
            return 0L;
        }
        Long size = redisTemplate.opsForStream().size(stream);
        return size == null ? 0L : size;
    }

    public long pendingCount(String stream, String group) {
        if (!isEnabled() || !StringUtils.hasText(stream) || !StringUtils.hasText(group)) {
            return 0L;
        }
        PendingMessagesSummary pending = redisTemplate.opsForStream().pending(stream, group);
        return pending == null ? 0L : pending.getTotalPendingMessages();
    }

    private void createGroup(String stream, String group) {
        redisTemplate.opsForStream().createGroup(stream, ReadOffset.from("0-0"), group);
    }

    private void trim(String stream) {
        long maxLength = queueProperties.getRedis().getTrimMaxLength();
        if (maxLength > 0) {
            redisTemplate.opsForStream().trim(stream, maxLength, true);
        }
    }

    private QueueMessage toQueueMessage(MapRecord<String, Object, Object> record) {
        Map<String, String> body = new LinkedHashMap<>();
        record.getValue().forEach((key, value) ->
                body.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
        return new QueueMessage(record.getStream(), record.getId().getValue(), Map.copyOf(body));
    }

    private Map<String, String> normalizePayload(Map<String, String> fields) {
        Map<String, String> payload = new LinkedHashMap<>();
        if (fields == null) {
            return payload;
        }
        fields.forEach((key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
                payload.put(key, value);
            }
        });
        return payload;
    }

    private boolean isBusyGroup(Exception e) {
        return message(e).contains("BUSYGROUP");
    }

    private boolean isMissingStream(Exception e) {
        String message = message(e);
        return message.contains("NO SUCH KEY") || message.contains("REQUIRES THE KEY TO EXIST");
    }

    private String message(Exception e) {
        StringBuilder messages = new StringBuilder();
        Throwable current = e;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (current.getMessage() != null) {
                messages.append(' ').append(current.getMessage());
            }
            current = current.getCause();
        }
        return messages.toString().toUpperCase(Locale.ROOT);
    }
}
