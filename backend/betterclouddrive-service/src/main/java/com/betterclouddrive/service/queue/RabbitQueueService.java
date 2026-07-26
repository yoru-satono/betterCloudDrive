package com.betterclouddrive.service.queue;

import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.observability.AsyncTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RabbitQueueService {
    private final RabbitTemplate rabbitTemplate;
    private final RabbitAdmin rabbitAdmin;
    private final ObjectMapper objectMapper;
    private final QueueProperties properties;
    private final AsyncTraceService asyncTraceService;

    public String publish(String routingKey, Map<String, String> body) {
        try {
            String messageId = UUID.randomUUID().toString();
            MessageProperties messageProperties = new MessageProperties();
            messageProperties.setMessageId(messageId);
            messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            messageProperties.setContentEncoding(StandardCharsets.UTF_8.name());
            messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            asyncTraceService.injectCurrent(messageProperties);
            Message message = new Message(objectMapper.writeValueAsBytes(body), messageProperties);
            CorrelationData correlation = new CorrelationData(messageId);
            rabbitTemplate.send(properties.getRabbitmq().getExchange(), routingKey, message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
            if (!confirm.ack() || correlation.getReturned() != null) {
                return "";
            }
            return messageId;
        } catch (Exception e) {
            throw new IllegalStateException("RabbitMQ publish failed", e);
        }
    }

    public Map<String, String> read(Message message) {
        try {
            Map<?, ?> raw = objectMapper.readValue(message.getBody(), Map.class);
            Map<String, String> result = new LinkedHashMap<>();
            raw.forEach((key, value) -> result.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
            return Map.copyOf(result);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid RabbitMQ message payload", e);
        }
    }

    public long messageCount(String queue) {
        var info = rabbitAdmin.getQueueInfo(queue);
        return info == null ? 0L : info.getMessageCount();
    }
}
