package com.betterclouddrive.service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "queue")
public class QueueProperties {

    private boolean enabled = true;
    private Redis redis = new Redis();

    @Data
    public static class Redis {
        private String consumerName = "backend-worker";
        private int readBatchSize = 20;
        private long blockTimeoutMs = 2000;
        private int maxDeliveries = 5;
        private long claimIdleMs = 60000;
        private long trimMaxLength = 10000;
        private int outboxPublishBatchSize = 50;
        private long outboxPublishIntervalMs = 5000;
        private long notificationDispatchIntervalMs = 2000;
        private long teamTaskDispatchIntervalMs = 1000;
        private long backlogWarnThreshold = 100;
        private long deadLetterWarnThreshold = 1;
        private Streams streams = new Streams();
        private Groups groups = new Groups();
    }

    @Data
    public static class Streams {
        private String teamEvents = "bcd:stream:team-events";
        private String notifications = "bcd:stream:notifications";
        private String teamTasks = "bcd:stream:team-tasks";
        private String deadLetter = "bcd:stream:dead-letter";
    }

    @Data
    public static class Groups {
        private String teamEvents = "bcd-team-event-workers";
        private String notifications = "bcd-notification-workers";
        private String teamTasks = "bcd-team-task-workers";
    }
}
