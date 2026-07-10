package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.FolderZipTaskService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FolderZipTaskTest {

    @Test
    void processPendingFolderZips_shouldDelegateConfiguredBatchSize() {
        FolderZipTaskService folderZipTaskService = Mockito.mock(FolderZipTaskService.class);
        FolderZipTask task = new FolderZipTask(folderZipTaskService);
        ReflectionTestUtils.setField(task, "batchSize", 3);
        when(folderZipTaskService.processPendingFolderZips(3)).thenReturn(1);

        task.processPendingFolderZips();

        verify(folderZipTaskService).processPendingFolderZips(3);
    }
}
