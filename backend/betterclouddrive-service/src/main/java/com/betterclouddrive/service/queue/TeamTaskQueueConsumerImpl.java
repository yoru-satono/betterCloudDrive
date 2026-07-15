package com.betterclouddrive.service.queue;

import com.betterclouddrive.service.*;
import com.rabbitmq.client.Channel;
import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.metrics.QueueMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import java.io.IOException;

@Service
@RequiredArgsConstructor
@Slf4j
public class TeamTaskQueueConsumerImpl {
    private final RabbitQueueService queue;
    private final QueueProperties properties;
    private final TeamAuditExportTaskService auditExports;
    private final ThumbnailService thumbnails;
    private final FolderZipTaskService folderZips;
    private final OnlyOfficeSaveService officeSaves;
    private final QueueMetrics metrics;

    @RabbitListener(queues = "${queue.rabbitmq.team-tasks-queue:bcd.team-tasks}", autoStartup = "${queue.enabled:true}")
    public void processQueuedTask(Message raw, Channel channel) throws IOException {
        metrics.beginConsumer("team-tasks");
        long started = metrics.startProcessing();
        long tag = raw.getMessageProperties().getDeliveryTag();
        String type = null;
        try {
            QueueMessage message = new QueueMessage("team-tasks", raw.getMessageProperties().getMessageId(), queue.read(raw));
            type = message.body().get("targetType");
            int claimed = switch (type == null ? "" : type) {
                case "AUDIT_EXPORT" -> auditExports.processPendingExports(1);
                case "THUMBNAIL" -> thumbnails.processPendingThumbnails(1);
                case "FOLDER_ZIP" -> folderZips.processPendingFolderZips(1);
                case "ONLYOFFICE_SAVE" -> officeSaves.processPendingSaves(1);
                default -> -1;
            };
            if (claimed < 0) {
                metrics.recordDeadLetter("team-tasks");
                channel.basicReject(tag, false);
                metrics.recordProcessing("team-task-dispatch", "failure", started);
                return;
            }
            if (claimed == 0) metrics.recordDuplicateTaskWakeup(type);
            channel.basicAck(tag, false);
            metrics.recordProcessing("team-task-dispatch", "success", started);
        } catch (RuntimeException error) {
            metrics.recordDeadLetter("team-tasks");
            channel.basicReject(tag, false);
            metrics.recordProcessing("team-task-dispatch", "failure", started);
            log.warn("Team task wakeup failed and will be recovered by the database scanner: taskType={}", type, error);
        } finally {
            metrics.endConsumer("team-tasks");
        }
    }
}
