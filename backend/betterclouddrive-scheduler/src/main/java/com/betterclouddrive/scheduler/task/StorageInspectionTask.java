package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.common.exception.InspectionException;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository;
import com.betterclouddrive.dal.repository.StorageInspectionRepository;
import com.betterclouddrive.service.inspection.InspectionMetrics;
import com.betterclouddrive.service.inspection.InspectionProperties;
import com.betterclouddrive.service.inspection.InspectionWorker;
import com.betterclouddrive.service.inspection.StorageInspectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StorageInspectionTask {
    private final InspectionWorker worker;
    private final StorageInspectionService inspection;
    private final StorageInspectionRepository repository;
    private final QuotaAccountingRepository accounting;
    private final InspectionProperties properties;
    private final InspectionMetrics metrics;

    @Scheduled(fixedDelayString="${drive.inspection.dispatch-ms:1000}")
    public void dispatch() { worker.dispatch(); }

    @Scheduled(fixedDelayString="${drive.inspection.maintenance-ms:60000}")
    public void maintain() {
        metrics.refresh();
        inspection.pruneDerivedData();
    }

    @Scheduled(cron="${drive.inspection.cron:0 15 2 * * *}", zone="${drive.inspection.zone:Asia/Shanghai}")
    public void daily() {
        if (properties.isEnabled() && properties.isScheduled()) start();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void baseline() {
        if (properties.isEnabled() && properties.isScheduled() && accounting.ready() && repository.runCount() == 0) start();
    }

    private void start() {
        try { inspection.start(null); }
        catch (InspectionException error) { log.info("Scheduled inspection not queued; reason={}", error.getMessageKey()); }
    }
}
