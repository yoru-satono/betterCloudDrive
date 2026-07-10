package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.service.metrics.QueueMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueMetricsTask {

    private final QueueMetrics queueMetrics;

    @Scheduled(fixedDelayString = "${observability.metrics.queue-refresh-interval-ms:15000}")
    public void refresh() {
        QueueMetrics.QueueSnapshot snapshot = queueMetrics.refresh();
        log.debug(
                "Refreshed queue metrics: streamMessages={}, consumerPending={}, databasePending={}, retries={}, deadLetters={}",
                snapshot.streamMessages(),
                snapshot.consumerPending(),
                snapshot.databasePending(),
                snapshot.retries(),
                snapshot.deadLetters());
    }
}
