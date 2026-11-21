package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.service.FileVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OldVersionCleanupTask {
    private final FileRepository fileRepository;
    private final FileVersionService fileVersionService;

    @Value("${drive.version.max-versions:10}")
    private int maxVersions;

    @Scheduled(cron = "0 0 4 * * ?")
    public void cleanupOldVersions() {
        long after = 0;
        int total = 0;
        while (true) {
            var candidates = fileRepository.findVersionCleanupCandidates(after, maxVersions, PageRequest.of(0, 500));
            if (candidates.isEmpty()) break;
            for (Long fileId : candidates) {
                try { total += fileVersionService.cleanupOldVersions(fileId, maxVersions); }
                catch (RuntimeException error) { log.warn("Version retention failed for file id={}", fileId); }
                after = fileId;
            }
        }
        log.info("Version retention removed {} records", total);
    }
}
