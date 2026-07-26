package com.betterclouddrive.service.queue;

import com.betterclouddrive.service.*;
import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.metrics.QueueMetrics;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import java.util.Map;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamTaskQueueConsumerImplTest {
    @Mock RabbitQueueService rabbit; @Mock TeamAuditExportTaskService audit; @Mock ThumbnailService thumbnails;
    @Mock FolderZipTaskService zip; @Mock OnlyOfficeSaveService office; @Mock QueueMetrics metrics; @Mock Channel channel;
    TeamTaskQueueConsumerImpl consumer;
    @BeforeEach void setUp() {
        consumer = new TeamTaskQueueConsumerImpl(
                rabbit, new QueueProperties(), audit, thumbnails, zip, office, metrics,
                new com.betterclouddrive.service.observability.AsyncTraceService(
                        io.opentelemetry.api.OpenTelemetry.noop()));
    }

    @Test void routesAllSupportedTaskTypesAndAcknowledges() throws Exception {
        invoke("AUDIT_EXPORT"); verify(audit).processPendingExports(1);
        invoke("THUMBNAIL"); verify(thumbnails).processPendingThumbnails(1);
        invoke("FOLDER_ZIP"); verify(zip).processPendingFolderZips(1);
        invoke("ONLYOFFICE_SAVE"); verify(office).processPendingSaves(1);
        verify(channel, times(4)).basicAck(7L, false);
    }

    @Test void unknownTypeIsRejectedToDeadLetter() throws Exception {
        invoke("TEAM_CLEANUP");
        verify(channel).basicReject(7L, false); verify(metrics).recordDeadLetter("team-tasks");
    }

    @Test void workerFailureIsRejectedAndDatabaseScannerRemainsRecoveryPath() throws Exception {
        when(rabbit.read(any())).thenReturn(Map.of("targetType", "AUDIT_EXPORT"));
        when(audit.processPendingExports(1)).thenThrow(new IllegalStateException("down"));
        consumer.processQueuedTask(message(), channel);
        verify(channel).basicReject(7L, false); verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    private void invoke(String type) throws Exception { when(rabbit.read(any())).thenReturn(Map.of("targetType", type)); consumer.processQueuedTask(message(), channel); }
    private Message message() { var p = new MessageProperties(); p.setDeliveryTag(7L); p.setMessageId("id"); return new Message(new byte[0], p); }
}
