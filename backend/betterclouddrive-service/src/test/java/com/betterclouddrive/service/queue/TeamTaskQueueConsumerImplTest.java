package com.betterclouddrive.service.queue;

import com.betterclouddrive.service.*;
import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.metrics.QueueMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamTaskQueueConsumerImplTest {
    @Mock RedisStreamQueueService queue;
    @Mock TeamAuditExportTaskService audit;
    @Mock ThumbnailService thumbnails;
    @Mock FolderZipTaskService zip;
    @Mock OnlyOfficeSaveService office;
    @Mock QueueMetrics metrics;
    QueueProperties properties;
    TeamTaskQueueConsumerImpl consumer;

    @BeforeEach void setUp() {
        properties = new QueueProperties();
        consumer = new TeamTaskQueueConsumerImpl(queue, properties, audit, thumbnails, zip, office, metrics);
    }

    @Test void duplicateWakeupsUseAtomicClaimAndAreBothAcknowledged() {
        QueueMessage first = message("1-0", "FOLDER_ZIP");
        QueueMessage duplicate = message("2-0", "FOLDER_ZIP");
        when(queue.readGroup(anyString(), anyString(), anyString(), anyInt(), any(Duration.class)))
                .thenReturn(List.of(first, duplicate));
        when(zip.processPendingFolderZips(1)).thenReturn(1, 0);

        assertThat(consumer.processQueuedTasks(10)).isEqualTo(2);
        verify(zip, times(2)).processPendingFolderZips(1);
        verify(queue, times(2)).acknowledge(eq("bcd:stream:team-tasks"), eq("bcd-team-task-workers"), anyString());
        verify(metrics).recordDuplicateTaskWakeup("FOLDER_ZIP");
    }

    @Test void dispatchesAllMigratedTaskTypes() {
        when(queue.readGroup(anyString(), anyString(), anyString(), anyInt(), any(Duration.class))).thenReturn(List.of(
                message("1", "AUDIT_EXPORT"), message("2", "THUMBNAIL"),
                message("3", "FOLDER_ZIP"), message("4", "ONLYOFFICE_SAVE")));
        assertThat(consumer.processQueuedTasks(10)).isEqualTo(4);
        verify(audit).processPendingExports(1);
        verify(thumbnails).processPendingThumbnails(1);
        verify(zip).processPendingFolderZips(1);
        verify(office).processPendingSaves(1);
    }

    @Test void redisReadFailureDoesNotTouchDatabaseWorkers() {
        when(queue.readGroup(anyString(), anyString(), anyString(), anyInt(), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.processQueuedTasks(10))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(audit, thumbnails, zip, office);
    }

    private QueueMessage message(String id, String type) {
        return new QueueMessage("bcd:stream:team-tasks", id, Map.of("targetType", type, "aggregateId", "77"));
    }
}
