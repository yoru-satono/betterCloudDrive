package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FileVersionEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.FileVersionRepository;
import com.betterclouddrive.storage.StorageService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OldVersionCleanupTask {

    private final FileRepository fileRepository;
    private final FileVersionRepository fileVersionRepository;
    private final StorageService storageService;

    @Value("${drive.version.max-versions:10}")
    private int maxVersions;

    @Scheduled(cron = "0 0 4 * * ?") // Daily at 4:00 AM
    @Transactional
    public void cleanupOldVersions() {
        Specification<FileEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.greaterThan(root.get("versionCount"), maxVersions));
            predicates.add(cb.equal(root.get("isDeleted"), false));
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        List<FileEntity> files = fileRepository.findAll(spec);

        int totalDeleted = 0;
        for (FileEntity file : files) {
            try {
                List<FileVersionEntity> versions = fileVersionRepository.findTopVersionsByFileId(
                        file.getId(), PageRequest.of(0, 1000));
                if (versions.size() <= maxVersions) {
                    continue;
                }

                List<FileVersionEntity> toDelete = versions.subList(maxVersions, versions.size());
                for (FileVersionEntity v : toDelete) {
                    storageService.deleteObject(v.getStoragePath());
                    fileVersionRepository.deleteById(v.getId());
                    totalDeleted++;
                }
                file.setVersionCount(maxVersions);
                fileRepository.save(file);
            } catch (Exception e) {
                log.warn("Failed to cleanup old versions for file: {}", file.getId(), e);
            }
        }
        log.info("Cleaned up {} old file versions across {} files", totalDeleted, files.size());
    }
}
