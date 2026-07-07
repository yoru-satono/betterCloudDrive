package com.betterclouddrive.service.impl;

import com.betterclouddrive.dal.entity.FileVersionEntity;
import com.betterclouddrive.dal.entity.OnlyOfficeCallbackEntity;
import com.betterclouddrive.service.FileVersionService;
import com.betterclouddrive.service.NotificationService;
import com.betterclouddrive.service.OnlyOfficeCallbackService;
import com.betterclouddrive.service.OnlyOfficeContentDownloader;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnlyOfficeSaveServiceImplTest {

    @Mock
    private OnlyOfficeCallbackService onlyOfficeCallbackService;

    @Mock
    private OnlyOfficeContentDownloader onlyOfficeContentDownloader;

    @Mock
    private FileVersionService fileVersionService;

    @Mock
    private TeamTaskService teamTaskService;

    @Mock
    private TeamAuditService teamAuditService;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private OnlyOfficeSaveServiceImpl onlyOfficeSaveService;

    @Test
    void processPendingSaves_shouldSaveVersionAndMarkTaskSucceeded() {
        OnlyOfficeCallbackEntity callback = callback();
        byte[] content = "edited".getBytes();
        FileVersionEntity version = FileVersionEntity.builder().id(99L).versionNumber(3).build();
        when(onlyOfficeCallbackService.listPendingSaves(20)).thenReturn(List.of(callback));
        when(onlyOfficeContentDownloader.download("http://onlyoffice/output.docx")).thenReturn(content);
        when(fileVersionService.saveTeamEditedVersion(10L, 2L, 1L, content)).thenReturn(version);

        int processed = onlyOfficeSaveService.processPendingSaves(20);

        assertThat(processed).isEqualTo(1);
        verify(teamTaskService).markRunning(77L);
        verify(onlyOfficeCallbackService).markSaved(88L, 99L);
        verify(teamTaskService).markSucceeded(77L, "保存为版本 3");
    }

    @Test
    void processPendingSaves_shouldMarkCallbackAndTaskFailed() {
        OnlyOfficeCallbackEntity callback = callback();
        when(onlyOfficeCallbackService.listPendingSaves(20)).thenReturn(List.of(callback));
        when(onlyOfficeContentDownloader.download("http://onlyoffice/output.docx"))
                .thenThrow(new RuntimeException("download failed"));

        int processed = onlyOfficeSaveService.processPendingSaves(20);

        assertThat(processed).isEqualTo(1);
        verify(onlyOfficeCallbackService).markFailed(88L, "download failed");
        verify(teamTaskService).markFailed(77L, "download failed");
        verify(teamAuditService).recordFailure(10L, 2L, "OFFICE_SAVE_FAILED", "FILE", 1L, 500003, "download failed");
        verify(notificationService).notifyUser(
                2L,
                10L,
                "TEAM_OFFICE_SAVE_FAILED",
                "在线文档保存失败",
                "文档保存失败，请在团队任务中心重试。原因：download failed",
                "/teams/10/settings",
                false);
    }

    private OnlyOfficeCallbackEntity callback() {
        return OnlyOfficeCallbackEntity.builder()
                .id(88L)
                .teamId(10L)
                .fileId(1L)
                .actorUserId(2L)
                .taskId(77L)
                .downloadUrl("http://onlyoffice/output.docx")
                .build();
    }
}
