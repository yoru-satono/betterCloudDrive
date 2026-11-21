package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.service.FileVersionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OldVersionCleanupTaskTest {
    @Mock FileRepository files;
    @Mock FileVersionService versions;
    @InjectMocks OldVersionCleanupTask task;

    @Test void resumesAfterFailedFileAndUsesKeysetPages() {
        ReflectionTestUtils.setField(task, "maxVersions", 3);
        when(files.findVersionCleanupCandidates(eq(0L), eq(3), any())).thenReturn(List.of(1L, 2L));
        when(files.findVersionCleanupCandidates(eq(2L), eq(3), any())).thenReturn(List.of(4L));
        when(files.findVersionCleanupCandidates(eq(4L), eq(3), any())).thenReturn(List.of());
        when(versions.cleanupOldVersions(1L, 3)).thenThrow(new IllegalStateException());
        when(versions.cleanupOldVersions(2L, 3)).thenReturn(1);
        when(versions.cleanupOldVersions(4L, 3)).thenReturn(2);
        task.cleanupOldVersions();
        verify(versions).cleanupOldVersions(1L, 3);
        verify(versions).cleanupOldVersions(2L, 3);
        verify(versions).cleanupOldVersions(4L, 3);
    }

    @Test void emptyPageDoesNotInvokeBusinessMutations() {
        ReflectionTestUtils.setField(task, "maxVersions", 3);
        when(files.findVersionCleanupCandidates(anyLong(), eq(3), any())).thenReturn(List.of());
        task.cleanupOldVersions();
        verifyNoInteractions(versions);
    }
}
