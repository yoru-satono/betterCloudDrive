package com.betterclouddrive.service.queue;

import com.betterclouddrive.service.config.QueueProperties;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@ConditionalOnProperty(prefix = "queue", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RabbitQueueConfig {
    @Bean
    TopicExchange queueExchange(QueueProperties properties) {
        return new TopicExchange(properties.getRabbitmq().getExchange(), true, false);
    }

    @Bean
    DirectExchange deadLetterExchange(QueueProperties properties) {
        return new DirectExchange(properties.getRabbitmq().getDeadLetterExchange(), true, false);
    }

    @Bean
    Queue notificationsQueue(QueueProperties properties) {
        return durableQueue(properties.getRabbitmq().getNotificationsQueue(), properties);
    }

    @Bean
    Queue teamTasksQueue(QueueProperties properties) {
        return durableQueue(properties.getRabbitmq().getTeamTasksQueue(), properties);
    }

    @Bean
    Queue deadLetterQueue(QueueProperties properties) {
        return QueueBuilder.durable(properties.getRabbitmq().getDeadLetterQueue()).build();
    }

    @Bean
    Binding notificationsBinding(Queue notificationsQueue, TopicExchange queueExchange, QueueProperties properties) {
        return BindingBuilder.bind(notificationsQueue).to(queueExchange)
                .with(properties.getRabbitmq().getNotificationsRoutingKey());
    }

    @Bean
    Binding teamTasksBinding(Queue teamTasksQueue, TopicExchange queueExchange, QueueProperties properties) {
        return BindingBuilder.bind(teamTasksQueue).to(queueExchange)
                .with(properties.getRabbitmq().getTeamTasksRoutingKey());
    }

    @Bean
    Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with("dead-letter");
    }

    @Bean
    RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        return template;
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, QueueProperties properties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(properties.getRabbitmq().getPrefetch());
        factory.setConcurrentConsumers(properties.getRabbitmq().getConcurrency());
        factory.setMaxConcurrentConsumers(properties.getRabbitmq().getMaxConcurrency());
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    private Queue durableQueue(String name, QueueProperties properties) {
        return QueueBuilder.durable(name).withArguments(Map.of(
                "x-dead-letter-exchange", properties.getRabbitmq().getDeadLetterExchange(),
                "x-dead-letter-routing-key", "dead-letter"
        )).build();
    }
}
