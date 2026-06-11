package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.dal.repository.ShareLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShareStatsSyncTask {

    private final StringRedisTemplate redisTemplate;
    private final ShareLinkRepository shareLinkRepository;

    private static final String SHARE_VISITS_KEY = "share:visits";

    @Scheduled(fixedRateString = "${drive.share.visit-sync-interval-ms:300000}")
    @Transactional
    public void syncShareVisitCounts() {
        Set<ZSetOperations.TypedTuple<String>> entries =
                redisTemplate.opsForZSet().reverseRangeWithScores(SHARE_VISITS_KEY, 0L, -1L);
        if (entries == null || entries.isEmpty()) {
            return;
        }

        for (ZSetOperations.TypedTuple<String> entry : entries) {
            try {
                String shareCode = entry.getValue();
                int count = (int) Math.round(entry.getScore());
                if (shareCode != null && count > 0) {
                    shareLinkRepository.incrementVisitCount(shareCode, count);
                }
            } catch (Exception e) {
                log.warn("Failed to sync share visit count", e);
            }
        }
        redisTemplate.delete(SHARE_VISITS_KEY);
    }
}
