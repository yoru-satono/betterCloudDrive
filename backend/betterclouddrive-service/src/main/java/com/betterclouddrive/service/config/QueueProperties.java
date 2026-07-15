package com.betterclouddrive.service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "queue")
public class QueueProperties {

    private boolean enabled = true;
    private Rabbitmq rabbitmq = new Rabbitmq();

    @Data
    public static class Rabbitmq {
        private String exchange = "bcd.events";
        private String deadLetterExchange = "bcd.dead-letter";
        private String notificationsQueue = "bcd.notifications";
        private String teamTasksQueue = "bcd.team-tasks";
        private String deadLetterQueue = "bcd.dead-letter";
        private String notificationsRoutingKey = "notification.dispatch";
        private String teamTasksRoutingKey = "team-task.wakeup";
        private int prefetch = 20;
        private int concurrency = 1;
        private int maxConcurrency = 4;
        private int maxDeliveries = 5;
        private int outboxPublishBatchSize = 50;
        private long outboxPublishIntervalMs = 5000;
        private long backlogWarnThreshold = 100;
        private long deadLetterWarnThreshold = 1;
    }
}
