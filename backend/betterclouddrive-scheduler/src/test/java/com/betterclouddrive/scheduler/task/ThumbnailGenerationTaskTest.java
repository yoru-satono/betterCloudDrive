package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.ThumbnailService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ThumbnailGenerationTaskTest {

    @Test
    void processPendingThumbnails_shouldDelegateConfiguredBatchSize() {
        ThumbnailService thumbnailService = Mockito.mock(ThumbnailService.class);
        ThumbnailGenerationTask task = new ThumbnailGenerationTask(thumbnailService);
        ReflectionTestUtils.setField(task, "batchSize", 7);
        when(thumbnailService.processPendingThumbnails(7)).thenReturn(2);

        task.processPendingThumbnails();

        verify(thumbnailService).processPendingThumbnails(7);
    }
}
