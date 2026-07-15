package com.betterclouddrive.service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "drive.tasks")
public class TeamTaskProperties {
    private String resultDirectory = "./data/team-task-results";
    private long resultRetentionHours = 24;
    private int auditExportBatchSize = 10;
    private long auditExportIntervalMs = 5000;
    private long resultCleanupIntervalMs = 3600000;
    private long runningTimeoutSeconds = 1800;
}
