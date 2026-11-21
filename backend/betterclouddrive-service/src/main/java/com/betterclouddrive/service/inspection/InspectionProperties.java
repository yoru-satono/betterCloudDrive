package com.betterclouddrive.service.inspection;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "drive.inspection")
public class InspectionProperties {
    private boolean enabled = true;
    private boolean scheduled = true;
    private String cron = "0 15 2 * * *";
    private String zone = "Asia/Shanghai";
    private String sourceZone = "UTC";
    private int pageSize = 1000;
    private long observationGapSeconds = 600;
    private long orphanGraceSeconds = 86400;
    private long runTimeoutSeconds = 7200;
    private long leaseSeconds = 180;
    private int retentionBatchSize = 5000;
    private long pendingTaskSeconds = 7200;
    private long uploadExpireSeconds = -1;
    private long uploadCleanupGraceSeconds = 1800;
    private long runningTaskSeconds = -1;
    @org.springframework.beans.factory.annotation.Value("${drive.upload.session-expire-hours:24}")
    private long uploadSessionExpireHours = 24;
    @org.springframework.beans.factory.annotation.Value("${drive.tasks.running-timeout-seconds:1800}")
    private long teamTaskRunningTimeout = 1800;

    public long getUploadExpireSeconds() {
        return uploadExpireSeconds < 0 ? Math.multiplyExact(uploadSessionExpireHours, 3600) : uploadExpireSeconds;
    }

    public long getRunningTaskSeconds() {
        return runningTaskSeconds < 0 ? teamTaskRunningTimeout : runningTaskSeconds;
    }

    @jakarta.annotation.PostConstruct
    public void validate() {
        java.time.ZoneId.of(zone);
        java.time.ZoneId.of(sourceZone);
        if (pageSize < 1 || pageSize > 1000 || observationGapSeconds < 600 || orphanGraceSeconds < 86400
                || runTimeoutSeconds < 60 || leaseSeconds < 150 || leaseSeconds > 3600
                || getUploadExpireSeconds() < 1 || getRunningTaskSeconds() < 1 || pendingTaskSeconds < 1
                || uploadCleanupGraceSeconds < 0) throw new IllegalArgumentException("invalid_inspection_configuration");
        if (retentionBatchSize < 1 || retentionBatchSize > 10000) throw new IllegalArgumentException("invalid_inspection_configuration");
    }
}
