package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.dal.entity.UploadSessionEntity;
import com.betterclouddrive.dal.mapper.UploadSessionMapper;
import com.betterclouddrive.storage.StorageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredUploadSessionCleanupTask {

    private final UploadSessionMapper uploadSessionMapper;
    private final StorageService storageService;
    private final StringRedisTemplate redisTemplate;

    @Value("${drive.upload.session-expire-hours:24}")
    private int expireHours;

    @Scheduled(cron = "0 */30 * * * ?") // Every 30 minutes
    @Transactional
    public void cleanupExpiredUploadSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(expireHours);
        List<UploadSessionEntity> expired = uploadSessionMapper.selectList(
                new LambdaQueryWrapper<UploadSessionEntity>()
                        .eq(UploadSessionEntity::getStatus, 1) // uploading
                        .le(UploadSessionEntity::getCreatedAt, cutoff));

        if (expired.isEmpty()) {
            return;
        }

        for (UploadSessionEntity session : expired) {
            try {
                // Delete uploaded chunk parts from storage
                String chunkPrefix = "uploads/" + session.getUserId() + "/" + session.getId() + "/chunks";
                storageService.deleteParts(chunkPrefix, session.getTotalChunks());
                // Cleanup Redis bitmap
                redisTemplate.delete("upload:bitmap:" + session.getId());
                // Mark as expired
                session.setStatus(3);
                uploadSessionMapper.updateById(session);
            } catch (Exception e) {
                log.warn("Failed to cleanup upload session: {}", session.getId(), e);
            }
        }
        log.info("Cleaned up {} expired upload sessions", expired.size());
    }
}
