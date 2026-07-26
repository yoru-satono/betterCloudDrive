package com.betterclouddrive.service.queue;

import com.betterclouddrive.service.DeadLetterService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;

import static org.mockito.Mockito.*;

class DeadLetterQueueConsumerTest {
    @Test
    void acknowledgesOnlyAfterPersistence() throws Exception {
        DeadLetterService service = mock(DeadLetterService.class);
        Channel channel = mock(Channel.class);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(42L);
        Message message = new Message("{}".getBytes(), properties);

        new DeadLetterQueueConsumer(service).capture(message, channel);

        var order = inOrder(service, channel);
        order.verify(service).capture(message);
        order.verify(channel).basicAck(42L, false);
    }

    @Test
    void persistenceFailureRequeuesWithoutAcknowledging() throws Exception {
        DeadLetterService service = mock(DeadLetterService.class);
        Channel channel = mock(Channel.class);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(7L);
        Message message = new Message("{}".getBytes(), properties);
        doThrow(new IllegalStateException("database unavailable")).when(service).capture(message);

        new DeadLetterQueueConsumer(service).capture(message, channel);

        verify(channel).basicNack(7L, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
}
