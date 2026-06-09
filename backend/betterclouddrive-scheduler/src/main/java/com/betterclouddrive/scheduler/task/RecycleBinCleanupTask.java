package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.mapper.FileMapper;
import com.betterclouddrive.dal.mapper.FileVersionMapper;
import com.betterclouddrive.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class RecycleBinCleanupTask {

    private final FileMapper fileMapper;
    private final FileVersionMapper fileVersionMapper;
    private final StorageService storageService;
    private final StringRedisTemplate redisTemplate;

    @Value("${drive.recycle-bin.retention-days:30}")
    private int retentionDays;

    private static final int BATCH_SIZE = 500;

    @Scheduled(cron = "${drive.recycle-bin.cleanup-cron:0 0 3 * * ?}")
    @Transactional
    public void cleanupExpiredRecycleBinFiles() {
        log.info("Starting recycle bin cleanup...");
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);

        List<FileEntity> expiredFiles = fileMapper.selectExpiredDeletedFiles(cutoff, BATCH_SIZE);
        int totalCleaned = 0;

        while (!expiredFiles.isEmpty()) {
            for (FileEntity file : expiredFiles) {
                try {
                    // Delete from storage
                    if (file.getStoragePath() != null) {
                        storageService.deleteObject(file.getStoragePath());
                    }
                    if (file.getThumbnailPath() != null) {
                        storageService.deleteObject(file.getThumbnailPath());
                    }

                    // Delete file versions from storage
                    var versions = fileVersionMapper.selectByFileId(file.getId(), 100);
                    for (var version : versions) {
                        storageService.deleteObject(version.getStoragePath());
                    }

                    // Delete from DB (CASCADE handles versions, tags, favorites)
                    fileMapper.deleteById(file.getId());

                    // Decrement storage quota in Redis
                    if (file.getFileSize() > 0) {
                        redisTemplate.opsForValue().decrement(
                                "storage:incr:" + file.getUserId(), file.getFileSize());
                    }
                    totalCleaned++;
                } catch (Exception e) {
                    log.error("Failed to cleanup file: {}", file.getId(), e);
                }
            }

            log.info("Recycle bin cleanup progress: {} files cleaned", totalCleaned);
            expiredFiles = fileMapper.selectExpiredDeletedFiles(cutoff, BATCH_SIZE);
        }

        log.info("Recycle bin cleanup completed. Total cleaned: {}", totalCleaned);
    }
}
