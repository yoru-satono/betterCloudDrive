package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.OnlyOfficeSaveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OnlyOfficeSaveTask {

    private final OnlyOfficeSaveService onlyOfficeSaveService;

    @Value("${onlyoffice.save-worker-batch-size:20}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${onlyoffice.save-worker-interval-ms:60000}")
    public void processPendingSaves() {
        int processed = onlyOfficeSaveService.processPendingSaves(batchSize);
        if (processed > 0) {
            log.info("Processed {} pending OnlyOffice save callbacks", processed);
        }
    }
}
