package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.dal.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class StorageQuotaSyncTask {

    private final StringRedisTemplate redisTemplate;
    private final UserMapper userMapper;

    private static final String STORAGE_INCR_PREFIX = "storage:incr:";

    @Scheduled(fixedRateString = "${drive.storage.quota-sync-interval-ms:60000}")
    public void syncStorageQuota() {
        Set<String> keys = redisTemplate.keys(STORAGE_INCR_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return;
        }

        for (String key : keys) {
            try {
                Long userId = Long.parseLong(key.substring(STORAGE_INCR_PREFIX.length()));
                String deltaStr = redisTemplate.opsForValue().get(key);
                if (deltaStr != null) {
                    long delta = Long.parseLong(deltaStr);
                    if (delta != 0) {
                        userMapper.updateStorageUsed(userId, delta);
                    }
                }
                redisTemplate.delete(key);
            } catch (Exception e) {
                log.warn("Failed to sync storage quota for key: {}", key, e);
            }
        }
    }
}
