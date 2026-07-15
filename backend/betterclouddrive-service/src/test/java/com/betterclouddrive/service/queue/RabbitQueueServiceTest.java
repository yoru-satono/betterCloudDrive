package com.betterclouddrive.service.queue;

import com.betterclouddrive.service.config.QueueProperties;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RabbitQueueServiceTest {
    private final RabbitQueueService service = new RabbitQueueService(
            mock(RabbitTemplate.class), mock(RabbitAdmin.class), new ObjectMapper(), new QueueProperties());

    @Test void readsJsonPayloadAsStringMap() {
        var message = new Message("{\"outboxId\":42,\"targetType\":\"AUDIT_EXPORT\"}".getBytes(StandardCharsets.UTF_8), new MessageProperties());
        assertThat(service.read(message)).containsEntry("outboxId", "42").containsEntry("targetType", "AUDIT_EXPORT");
    }

    @Test void rejectsMalformedPayload() {
        assertThatThrownBy(() -> service.read(new Message("nope".getBytes(StandardCharsets.UTF_8), new MessageProperties())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
