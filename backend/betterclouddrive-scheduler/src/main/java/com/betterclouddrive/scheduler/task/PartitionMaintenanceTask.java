package com.betterclouddrive.scheduler.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PartitionMaintenanceTask {

    private final JdbcTemplate jdbcTemplate;

    /** Create next month's partition on the 1st of each month at 2:00 AM */
    @Scheduled(cron = "0 0 2 1 * ?")
    public void createNextMonthPartition() {
        try {
            jdbcTemplate.execute("SELECT create_monthly_partition()");
            log.info("Created next month's operation_log partition");
        } catch (Exception e) {
            log.warn("Failed to create next month partition", e);
        }
    }

    /** Drop partitions older than 6 months — weekly at 3:30 AM on Sundays */
    @Scheduled(cron = "0 30 3 ? * SUN")
    public void dropOldPartitions() {
        try {
            jdbcTemplate.execute("SELECT drop_old_partitions(6)");
            log.info("Dropped old operation_log partitions (retention: 6 months)");
        } catch (Exception e) {
            log.warn("Failed to drop old partitions", e);
        }
    }
}
