package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.dal.mapper.ShareLinkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShareStatsSyncTask {

    private final StringRedisTemplate redisTemplate;
    private final ShareLinkMapper shareLinkMapper;

    private static final String SHARE_VISITS_KEY = "share:visits";

    @Scheduled(fixedRateString = "${drive.share.visit-sync-interval-ms:300000}")
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
                    shareLinkMapper.incrementVisitCount(shareCode, count);
                }
            } catch (Exception e) {
                log.warn("Failed to sync share visit count", e);
            }
        }
        // Clear the counter
        redisTemplate.delete(SHARE_VISITS_KEY);
    }
}
