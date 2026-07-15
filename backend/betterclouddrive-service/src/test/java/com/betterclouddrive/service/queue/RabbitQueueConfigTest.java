package com.betterclouddrive.service.queue;

import com.betterclouddrive.service.config.QueueProperties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RabbitQueueConfigTest {
    private final QueueProperties properties = new QueueProperties();
    private final RabbitQueueConfig config = new RabbitQueueConfig();

    @Test void declaresDurableQueuesWithDeadLetterRouting() {
        var notifications = config.notificationsQueue(properties);
        var tasks = config.teamTasksQueue(properties);
        var dead = config.deadLetterQueue(properties);
        assertThat(notifications.isDurable()).isTrue();
        assertThat(tasks.isDurable()).isTrue();
        assertThat(dead.isDurable()).isTrue();
        assertThat(notifications.getArguments()).containsEntry("x-dead-letter-exchange", "bcd.dead-letter")
                .containsEntry("x-dead-letter-routing-key", "dead-letter");
    }

    @Test void bindsNotificationAndTaskRoutingKeys() {
        var exchange = config.queueExchange(properties);
        assertThat(config.notificationsBinding(config.notificationsQueue(properties), exchange, properties).getRoutingKey())
                .isEqualTo("notification.dispatch");
        assertThat(config.teamTasksBinding(config.teamTasksQueue(properties), exchange, properties).getRoutingKey())
                .isEqualTo("team-task.wakeup");
    }
}
