package com.betterclouddrive.scheduler.task;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.dal.repository.ShareLinkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.Set;

@ExtendWith(MockitoExtension.class)
class ShareStatsSyncTaskTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ShareLinkRepository shareLinkRepository;
    @Mock private ZSetOperations<String, String> zSetOps;
    @InjectMocks private ShareStatsSyncTask task;

    private ZSetOperations.TypedTuple<String> tuple(String code, double score) {
        return new ZSetOperations.TypedTuple<>() {
            @Override public String getValue() { return code; }
            @Override public Double getScore() { return score; }
            @Override public int compareTo(ZSetOperations.TypedTuple<String> o) {
                return Double.compare(score, o.getScore());
            }
        };
    }

    @Test
    void shouldSyncVisitCounts() {
        Set<ZSetOperations.TypedTuple<String>> entries = new LinkedHashSet<>();
        entries.add(tuple("abc123", 10.0));
        entries.add(tuple("xyz789", 5.0));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.reverseRangeWithScores("share:visits", 0L, -1L)).thenReturn(entries);

        task.syncShareVisitCounts();

        verify(shareLinkRepository).incrementVisitCount("abc123", 10);
        verify(shareLinkRepository).incrementVisitCount("xyz789", 5);
        verify(redisTemplate).delete("share:visits");
    }

    @Test
    void shouldDoNothingWhenNoData() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.reverseRangeWithScores("share:visits", 0L, -1L)).thenReturn(null);

        task.syncShareVisitCounts();

        verify(shareLinkRepository, never()).incrementVisitCount(any(), anyInt());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void shouldSkipZeroCountEntries() {
        Set<ZSetOperations.TypedTuple<String>> entries = new LinkedHashSet<>();
        entries.add(tuple("code001", 0.0));
        entries.add(tuple("code002", 3.0));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.reverseRangeWithScores("share:visits", 0L, -1L)).thenReturn(entries);

        task.syncShareVisitCounts();

        verify(shareLinkRepository, never()).incrementVisitCount(eq("code001"), anyInt());
        verify(shareLinkRepository).incrementVisitCount("code002", 3);
    }

    @Test
    void shouldDeleteRedisKeyAfterSync() {
        Set<ZSetOperations.TypedTuple<String>> entries = new LinkedHashSet<>();
        entries.add(tuple("abc123", 7.0));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.reverseRangeWithScores("share:visits", 0L, -1L)).thenReturn(entries);

        task.syncShareVisitCounts();

        verify(redisTemplate).delete("share:visits");
    }
}
