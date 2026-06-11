package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.dal.entity.ShareLinkEntity;
import com.betterclouddrive.dal.repository.ShareLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredShareCleanupTask {

    private final ShareLinkRepository shareLinkRepository;

    @Scheduled(cron = "0 0 * * * ?") // Every hour
    @Transactional
    public void cleanupExpiredShares() {
        List<ShareLinkEntity> expired = shareLinkRepository.findByIsCanceledFalseAndExpireAtLessThanEqual(LocalDateTime.now());
        if (expired.isEmpty()) {
            return;
        }

        for (ShareLinkEntity share : expired) {
            share.setIsCanceled(true);
            shareLinkRepository.save(share);
        }
        log.info("Cleaned up {} expired shares", expired.size());
    }
}
