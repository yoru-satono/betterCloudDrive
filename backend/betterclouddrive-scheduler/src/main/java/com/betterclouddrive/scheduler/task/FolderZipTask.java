package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.FolderZipTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FolderZipTask {

    private final FolderZipTaskService folderZipTaskService;

    @Value("${drive.tasks.folder-zip-batch-size:5}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${drive.tasks.folder-zip-interval-ms:5000}")
    public void processPendingFolderZips() {
        int processed = folderZipTaskService.processPendingFolderZips(batchSize);
        if (processed > 0) {
            log.info("Processed {} pending folder ZIP tasks", processed);
        }
    }
}
