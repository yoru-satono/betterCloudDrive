package com.betterclouddrive.service.queue;

import com.betterclouddrive.service.DeadLetterService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterQueueConsumer {
    private final DeadLetterService deadLetterService;

    @RabbitListener(queues = "${queue.rabbitmq.dead-letter-queue:bcd.dead-letter}",
            autoStartup = "${queue.enabled:true}", concurrency = "1")
    public void capture(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            deadLetterService.capture(message);
            channel.basicAck(tag, false);
        } catch (RuntimeException error) {
            log.error("Failed to persist dead-letter message; leaving it in RabbitMQ for retry", error);
            channel.basicNack(tag, false, true);
        }
    }
}
