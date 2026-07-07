package com.betterclouddrive.web.controller;

import com.betterclouddrive.dal.entity.FileVersionEntity;
import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.service.FileVersionService;
import com.betterclouddrive.service.OnlyOfficeCallbackService;
import com.betterclouddrive.service.TeamPermissionService;
import com.betterclouddrive.service.TeamTaskService;
import com.betterclouddrive.storage.StorageService;
import com.betterclouddrive.web.dto.request.OnlyOfficeCallbackRequest;
import com.betterclouddrive.web.service.OnlyOfficeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OfficeControllerCallbackTest {

    @Mock
    private FileService fileService;

    @Mock
    private TeamPermissionService teamPermissionService;

    @Mock
    private FileVersionService fileVersionService;

    @Mock
    private StorageService storageService;

    @Mock
    private OnlyOfficeService onlyOfficeService;

    @Mock
    private OnlyOfficeCallbackService onlyOfficeCallbackService;

    @Mock
    private TeamTaskService teamTaskService;

    private OfficeController controller;

    @BeforeEach
    void setUp() {
        controller = new OfficeController(
                fileService,
                fileVersionService,
                storageService,
                onlyOfficeService,
                onlyOfficeCallbackService,
                teamPermissionService,
                teamTaskService);
    }

    @Test
    void teamCallback_shouldSkipDuplicateSaveCallback() {
        OnlyOfficeCallbackRequest request = saveRequest();
        when(onlyOfficeService.validateCallbackToken("callback-token", 1L, 10L, "doc-key"))
                .thenReturn(2L);
        when(onlyOfficeCallbackService.callbackKey("doc-key")).thenReturn("doc-key");
        when(onlyOfficeCallbackService.contentUrlHash("http://onlyoffice/output.docx")).thenReturn("url-hash");
        when(onlyOfficeCallbackService.reserveSave(10L, 1L, "doc-key", 2, "url-hash")).thenReturn(false);

        Map<String, Integer> response = controller.teamCallback(10L, 1L, "callback-token", request);

        assertThat(response).containsEntry("error", 0);
        verify(teamPermissionService).requireWrite(2L, 10L, 1L);
        verify(teamTaskService, never()).startTask(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString());
        verify(onlyOfficeService, never()).downloadEditedContent(anyString());
        verify(fileVersionService, never()).saveTeamEditedVersion(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void teamCallback_shouldSaveFirstCallbackAndMarkIdempotencyRecord() {
        OnlyOfficeCallbackRequest request = saveRequest();
        byte[] content = "edited".getBytes();
        FileVersionEntity version = FileVersionEntity.builder().id(99L).versionNumber(3).build();
        TeamTaskEntity task = TeamTaskEntity.builder().id(77L).build();
        when(onlyOfficeService.validateCallbackToken("callback-token", 1L, 10L, "doc-key"))
                .thenReturn(2L);
        when(onlyOfficeCallbackService.callbackKey("doc-key")).thenReturn("doc-key");
        when(onlyOfficeCallbackService.contentUrlHash("http://onlyoffice/output.docx")).thenReturn("url-hash");
        when(onlyOfficeCallbackService.reserveSave(10L, 1L, "doc-key", 2, "url-hash")).thenReturn(true);
        when(teamTaskService.startTask(10L, 2L, "ONLYOFFICE_SAVE", "FILE", 1L, "保存在线编辑文档"))
                .thenReturn(task);
        when(onlyOfficeService.downloadEditedContent("http://onlyoffice/output.docx")).thenReturn(content);
        when(fileVersionService.saveTeamEditedVersion(10L, 2L, 1L, content)).thenReturn(version);

        Map<String, Integer> response = controller.teamCallback(10L, 1L, "callback-token", request);

        assertThat(response).containsEntry("error", 0);
        verify(onlyOfficeCallbackService).markSaved(10L, 1L, "doc-key", 2, "url-hash", 99L);
        verify(teamTaskService).markSucceeded(77L, "保存为版本 3");
    }

    @Test
    void teamCallback_shouldReleaseReservationWhenSaveFails() {
        OnlyOfficeCallbackRequest request = saveRequest();
        when(onlyOfficeService.validateCallbackToken("callback-token", 1L, 10L, "doc-key"))
                .thenReturn(2L);
        when(onlyOfficeCallbackService.callbackKey("doc-key")).thenReturn("doc-key");
        when(onlyOfficeCallbackService.contentUrlHash("http://onlyoffice/output.docx")).thenReturn("url-hash");
        when(onlyOfficeCallbackService.reserveSave(10L, 1L, "doc-key", 2, "url-hash")).thenReturn(true);
        when(teamTaskService.startTask(10L, 2L, "ONLYOFFICE_SAVE", "FILE", 1L, "保存在线编辑文档"))
                .thenReturn(TeamTaskEntity.builder().id(77L).build());
        when(onlyOfficeService.downloadEditedContent("http://onlyoffice/output.docx"))
                .thenThrow(new RuntimeException("download failed"));

        assertThatThrownBy(() -> controller.teamCallback(10L, 1L, "callback-token", request))
                .isInstanceOf(RuntimeException.class);

        verify(onlyOfficeCallbackService).releaseSaveReservation(10L, 1L, "doc-key", 2, "url-hash");
        verify(teamTaskService).markFailed(77L, "download failed");
    }

    private OnlyOfficeCallbackRequest saveRequest() {
        OnlyOfficeCallbackRequest request = new OnlyOfficeCallbackRequest();
        request.setStatus(2);
        request.setKey("doc-key");
        request.setUrl("http://onlyoffice/output.docx");
        return request;
    }
}
