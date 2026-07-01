package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.dal.repository.TeamRepository;
import com.betterclouddrive.dal.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class StorageQuotaSyncTask {

    private final StringRedisTemplate redisTemplate;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;

    private static final String STORAGE_INCR_PREFIX = "storage:incr:";
    private static final String TEAM_STORAGE_INCR_PREFIX = "team:storage:incr:";

    @Scheduled(fixedRateString = "${drive.storage.quota-sync-interval-ms:60000}")
    @Transactional
    public void syncStorageQuota() {
        syncKeys(STORAGE_INCR_PREFIX, (id, delta) -> userRepository.updateStorageUsed(id, delta));
        syncKeys(TEAM_STORAGE_INCR_PREFIX, (id, delta) -> teamRepository.updateStorageUsed(id, delta));
    }

    private void syncKeys(String prefix, StorageUpdater updater) {
        Set<String> keys = redisTemplate.keys(prefix + "*");
        if (keys == null || keys.isEmpty()) {
            return;
        }

        for (String key : keys) {
            try {
                Long id = Long.parseLong(key.substring(prefix.length()));
                String deltaStr = redisTemplate.opsForValue().get(key);
                if (deltaStr != null) {
                    long delta = Long.parseLong(deltaStr);
                    if (delta != 0) {
                        updater.update(id, delta);
                    }
                }
                redisTemplate.delete(key);
            } catch (Exception e) {
                log.warn("Failed to sync storage quota for key: {}", key, e);
            }
        }
    }

    private interface StorageUpdater {
        void update(Long id, long delta);
    }
}
