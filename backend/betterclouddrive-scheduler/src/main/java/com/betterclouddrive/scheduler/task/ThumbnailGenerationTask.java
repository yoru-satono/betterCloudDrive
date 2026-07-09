package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.ThumbnailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ThumbnailGenerationTask {

    private final ThumbnailService thumbnailService;

    @Value("${drive.tasks.thumbnail-batch-size:10}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${drive.tasks.thumbnail-interval-ms:5000}")
    public void processPendingThumbnails() {
        int processed = thumbnailService.processPendingThumbnails(batchSize);
        if (processed > 0) {
            log.info("Processed {} pending thumbnail tasks", processed);
        }
    }
}
