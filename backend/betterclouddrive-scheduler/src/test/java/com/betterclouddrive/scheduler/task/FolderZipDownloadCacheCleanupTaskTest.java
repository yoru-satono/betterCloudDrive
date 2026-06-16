package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.dal.entity.FolderZipDownloadCacheEntity;
import com.betterclouddrive.dal.repository.FolderZipDownloadCacheRepository;
import com.betterclouddrive.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FolderZipDownloadCacheCleanupTaskTest {

    @Mock private FolderZipDownloadCacheRepository cacheRepository;
    @Mock private StorageService storageService;
    @InjectMocks private FolderZipDownloadCacheCleanupTask task;

    @Test
    void shouldCleanupExpiredFolderZipCaches() {
        ReflectionTestUtils.setField(task, "retentionHours", 24);
        FolderZipDownloadCacheEntity cache = FolderZipDownloadCacheEntity.builder()
                .id(1L)
                .objectKey("folder-zips/1/2/cache.zip")
                .lastDownloadedAt(LocalDateTime.now().minusHours(25))
                .build();
        when(cacheRepository.findByLastDownloadedAtLessThanEqual(any())).thenReturn(List.of(cache));

        task.cleanupExpiredFolderZipCaches();

        verify(storageService).deleteObject("folder-zips/1/2/cache.zip");
        verify(cacheRepository).delete(cache);
    }
}
