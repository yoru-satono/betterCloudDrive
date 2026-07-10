package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.metrics.QueueMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueMetricsTaskTest {

    @Mock
    private QueueMetrics queueMetrics;

    @Test
    void refresh_shouldDelegateToMetrics() {
        when(queueMetrics.refresh()).thenReturn(new QueueMetrics.QueueSnapshot(1L, 2L, 3L, 4L, 5L));

        new QueueMetricsTask(queueMetrics).refresh();

        verify(queueMetrics).refresh();
    }
}
