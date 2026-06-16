package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.dal.entity.FolderZipDownloadCacheEntity;
import com.betterclouddrive.dal.repository.FolderZipDownloadCacheRepository;
import com.betterclouddrive.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FolderZipDownloadCacheCleanupTask {

    private final FolderZipDownloadCacheRepository cacheRepository;
    private final StorageService storageService;

    @Value("${drive.download.folder-zip.cache-retention-hours:24}")
    private int retentionHours;

    @Scheduled(cron = "${drive.download.folder-zip.cleanup-cron:0 */30 * * * ?}")
    @Transactional
    public void cleanupExpiredFolderZipCaches() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(retentionHours);
        List<FolderZipDownloadCacheEntity> expired = cacheRepository.findByLastDownloadedAtLessThanEqual(cutoff);
        if (expired.isEmpty()) {
            return;
        }

        for (FolderZipDownloadCacheEntity cache : expired) {
            try {
                storageService.deleteObject(cache.getObjectKey());
                cacheRepository.delete(cache);
            } catch (Exception e) {
                log.warn("Failed to cleanup folder ZIP cache: {}", cache.getId(), e);
            }
        }
        log.info("Cleaned up {} expired folder ZIP caches", expired.size());
    }
}
