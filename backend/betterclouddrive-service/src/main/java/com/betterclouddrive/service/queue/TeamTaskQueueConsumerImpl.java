package com.betterclouddrive.service.queue;

import com.betterclouddrive.service.*;
import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.metrics.QueueMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TeamTaskQueueConsumerImpl implements TeamTaskQueueConsumer {
    private final RedisStreamQueueService queue;
    private final QueueProperties properties;
    private final TeamAuditExportTaskService auditExports;
    private final ThumbnailService thumbnails;
    private final FolderZipTaskService folderZips;
    private final OnlyOfficeSaveService officeSaves;
    private final QueueMetrics metrics;

    @Override
    public int processQueuedTasks(int batchSize) {
        if (!properties.isEnabled()) return 0;
        QueueProperties.Redis redis = properties.getRedis();
        List<QueueMessage> messages = queue.readGroup(redis.getStreams().getTeamTasks(), redis.getGroups().getTeamTasks(),
                redis.getConsumerName(), batchSize, Duration.ofMillis(redis.getBlockTimeoutMs()));
        int processed = 0;
        for (QueueMessage message : messages) {
            String type = message.body().get("targetType");
            try {
                int claimed = switch (type == null ? "" : type) {
                    case "AUDIT_EXPORT" -> auditExports.processPendingExports(1);
                    case "THUMBNAIL" -> thumbnails.processPendingThumbnails(1);
                    case "FOLDER_ZIP" -> folderZips.processPendingFolderZips(1);
                    case "ONLYOFFICE_SAVE" -> officeSaves.processPendingSaves(1);
                    default -> { queue.moveToDeadLetter(message, "Unsupported team task type: " + type); yield -1; }
                };
                if (claimed == 0) metrics.recordDuplicateTaskWakeup(type);
                queue.acknowledge(message.stream(), redis.getGroups().getTeamTasks(), message.id());
                processed++;
            } catch (RuntimeException error) {
                // Leave the message pending. The durable DB scanner remains the recovery path.
                log.warn("Team task wakeup failed and will be recovered by the database scanner: messageId={}, taskType={}",
                        message.id(), type, error);
            }
        }
        return processed;
    }
}
