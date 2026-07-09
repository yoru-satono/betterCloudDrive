package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.TeamAuditExportTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TeamAuditExportTask {

    private final TeamAuditExportTaskService teamAuditExportTaskService;

    @Value("${drive.tasks.audit-export-batch-size:10}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${drive.tasks.audit-export-interval-ms:5000}")
    public void processPendingAuditExports() {
        int processed = teamAuditExportTaskService.processPendingExports(batchSize);
        if (processed > 0) {
            log.info("Processed {} pending team audit export tasks", processed);
        }
    }
}
