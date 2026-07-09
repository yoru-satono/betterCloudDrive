package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.TeamAuditExportTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TeamTaskResultCleanupTask {

    private final TeamAuditExportTaskService teamAuditExportTaskService;

    @Scheduled(fixedDelayString = "${drive.tasks.result-cleanup-interval-ms:3600000}")
    public void cleanupExpiredTaskResults() {
        int cleaned = teamAuditExportTaskService.cleanupExpiredResults();
        if (cleaned > 0) {
            log.info("Cleaned {} expired team task result files", cleaned);
        }
    }
}
