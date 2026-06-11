package com.betterclouddrive.scheduler.task;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.dal.entity.ShareLinkEntity;
import com.betterclouddrive.dal.repository.ShareLinkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

@ExtendWith(MockitoExtension.class)
class ExpiredShareCleanupTaskTest {

    @Mock private ShareLinkRepository shareLinkRepository;
    @InjectMocks private ExpiredShareCleanupTask task;

    @Test
    void shouldCancelExpiredShares() {
        ShareLinkEntity s1 = ShareLinkEntity.builder().id(1L).isCanceled(false)
                .expireAt(LocalDateTime.now().minusHours(2)).build();
        ShareLinkEntity s2 = ShareLinkEntity.builder().id(2L).isCanceled(false)
                .expireAt(LocalDateTime.now().minusMinutes(5)).build();
        when(shareLinkRepository.findByIsCanceledFalseAndExpireAtLessThanEqual(any(LocalDateTime.class)))
                .thenReturn(List.of(s1, s2));

        task.cleanupExpiredShares();

        assertThat(s1.getIsCanceled()).isTrue();
        assertThat(s2.getIsCanceled()).isTrue();
        verify(shareLinkRepository, times(2)).save(any(ShareLinkEntity.class));
    }

    @Test
    void shouldDoNothingWhenNoExpiredShares() {
        when(shareLinkRepository.findByIsCanceledFalseAndExpireAtLessThanEqual(any(LocalDateTime.class)))
                .thenReturn(List.of());

        task.cleanupExpiredShares();

        verify(shareLinkRepository, never()).save(any());
    }
}
