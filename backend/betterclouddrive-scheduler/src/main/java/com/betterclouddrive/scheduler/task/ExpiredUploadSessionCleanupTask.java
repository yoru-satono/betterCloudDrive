package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.dal.entity.UploadSessionEntity;
import com.betterclouddrive.dal.repository.UploadSessionRepository;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredUploadSessionCleanupTask {

    private final UploadSessionRepository uploadSessionRepository;
    private final StorageService storageService;
    private final StringRedisTemplate redisTemplate;

    @Value("${drive.upload.session-expire-hours:24}")
    private int expireHours;

    @Scheduled(cron = "0 */30 * * * ?") // Every 30 minutes
    @Transactional
    public void cleanupExpiredUploadSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(expireHours);
        List<UploadSessionEntity> expired = uploadSessionRepository.findByStatusAndCreatedAtLessThanEqual(1, cutoff);

        if (expired.isEmpty()) {
            return;
        }

        for (UploadSessionEntity session : expired) {
            try {
                String chunkPrefix = "uploads/" + session.getUserId() + "/" + session.getId() + "/chunks";
                storageService.deleteParts(chunkPrefix, session.getTotalChunks());
                redisTemplate.delete("upload:bitmap:" + session.getId());
                session.setStatus(3);
                uploadSessionRepository.save(session);
            } catch (Exception e) {
                log.warn("Failed to cleanup upload session: {}", session.getId(), e);
            }
        }
        log.info("Cleaned up {} expired upload sessions", expired.size());
    }
}
