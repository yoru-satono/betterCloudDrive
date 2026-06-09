package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FileVersionEntity;
import com.betterclouddrive.dal.mapper.FileMapper;
import com.betterclouddrive.dal.mapper.FileVersionMapper;
import com.betterclouddrive.storage.StorageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OldVersionCleanupTask {

    private final FileMapper fileMapper;
    private final FileVersionMapper fileVersionMapper;
    private final StorageService storageService;

    @Value("${drive.version.max-versions:10}")
    private int maxVersions;

    @Scheduled(cron = "0 0 4 * * ?") // Daily at 4:00 AM
    @Transactional
    public void cleanupOldVersions() {
        // Find files with more versions than allowed
        List<FileEntity> files = fileMapper.selectList(
                new LambdaQueryWrapper<FileEntity>()
                        .gt(FileEntity::getVersionCount, maxVersions)
                        .eq(FileEntity::getIsDeleted, false));

        int totalDeleted = 0;
        for (FileEntity file : files) {
            try {
                // Returns versions ordered by version_number DESC (newest first)
                List<FileVersionEntity> versions = fileVersionMapper.selectByFileId(file.getId(), 1000);
                if (versions.size() <= maxVersions) {
                    continue;
                }

                // Keep latest maxVersions, delete the rest (oldest ones at the end of the list)
                List<FileVersionEntity> toDelete = versions.subList(maxVersions, versions.size());
                for (FileVersionEntity v : toDelete) {
                    storageService.deleteObject(v.getStoragePath());
                    fileVersionMapper.deleteById(v.getId());
                    totalDeleted++;
                }
                file.setVersionCount(maxVersions);
                fileMapper.updateById(file);
            } catch (Exception e) {
                log.warn("Failed to cleanup old versions for file: {}", file.getId(), e);
            }
        }
        log.info("Cleaned up {} old file versions across {} files", totalDeleted, files.size());
    }
}
