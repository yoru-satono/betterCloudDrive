package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.OnlyOfficeSaveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnlyOfficeSaveTaskTest {

    @Mock
    private OnlyOfficeSaveService onlyOfficeSaveService;

    @InjectMocks
    private OnlyOfficeSaveTask task;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(task, "batchSize", 20);
    }

    @Test
    void processPendingSaves_shouldDelegateToService() {
        when(onlyOfficeSaveService.processPendingSaves(20)).thenReturn(2);

        task.processPendingSaves();

        verify(onlyOfficeSaveService).processPendingSaves(20);
    }
}
