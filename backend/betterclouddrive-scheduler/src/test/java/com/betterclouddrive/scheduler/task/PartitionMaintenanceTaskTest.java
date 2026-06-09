package com.betterclouddrive.scheduler.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartitionMaintenanceTaskTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private PartitionMaintenanceTask task;

    @Test
    void shouldCreateNextMonthPartition() {
        task.createNextMonthPartition();
        verify(jdbcTemplate).execute("SELECT create_monthly_partition()");
    }

    @Test
    void shouldDropOldPartitions() {
        task.dropOldPartitions();
        verify(jdbcTemplate).execute("SELECT drop_old_partitions(6)");
    }

    @Test
    void shouldCatchErrorOnCreatePartition() {
        doThrow(new RuntimeException("DB error")).when(jdbcTemplate).execute(anyString());
        // Should not throw — error is caught and logged
        task.createNextMonthPartition();
    }
}
