package com.betterclouddrive.web.controller;

import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.service.FileService;
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
        when(onlyOfficeService.resolveDocumentServerDownloadUrl("http://onlyoffice/output.docx"))
                .thenReturn("http://onlyoffice/output.docx");
        when(onlyOfficeCallbackService.reserveSave(
                10L, 1L, 2L, "doc-key", 2, "url-hash", "http://onlyoffice/output.docx"))
                .thenReturn(false);

        Map<String, Integer> response = controller.teamCallback(10L, 1L, "callback-token", request);

        assertThat(response).containsEntry("error", 0);
        verify(teamPermissionService).requireWrite(2L, 10L, 1L);
        verify(teamTaskService, never()).queueTask(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString());
        verify(onlyOfficeService, never()).downloadEditedContent(anyString());
    }

    @Test
    void teamCallback_shouldQueueFirstCallbackAndReturnFast() {
        OnlyOfficeCallbackRequest request = saveRequest();
        TeamTaskEntity task = TeamTaskEntity.builder().id(77L).build();
        when(onlyOfficeService.validateCallbackToken("callback-token", 1L, 10L, "doc-key"))
                .thenReturn(2L);
        when(onlyOfficeCallbackService.callbackKey("doc-key")).thenReturn("doc-key");
        when(onlyOfficeCallbackService.contentUrlHash("http://onlyoffice/output.docx")).thenReturn("url-hash");
        when(onlyOfficeService.resolveDocumentServerDownloadUrl("http://onlyoffice/output.docx"))
                .thenReturn("http://onlyoffice/output.docx");
        when(onlyOfficeCallbackService.reserveSave(
                10L, 1L, 2L, "doc-key", 2, "url-hash", "http://onlyoffice/output.docx"))
                .thenReturn(true);
        when(teamTaskService.queueTask(10L, 2L, "ONLYOFFICE_SAVE", "FILE", 1L, "保存在线编辑文档"))
                .thenReturn(task);

        Map<String, Integer> response = controller.teamCallback(10L, 1L, "callback-token", request);

        assertThat(response).containsEntry("error", 0);
        verify(onlyOfficeCallbackService).attachTask(10L, 1L, "doc-key", 2, "url-hash", 77L);
        verify(onlyOfficeService, never()).downloadEditedContent(anyString());
        verify(teamTaskService, never()).markSucceeded(anyLong(), anyString());
    }

    @Test
    void teamCallback_shouldIgnoreNonSaveStatus() {
        OnlyOfficeCallbackRequest request = saveRequest();
        request.setStatus(1);
        when(onlyOfficeService.validateCallbackToken("callback-token", 1L, 10L, "doc-key"))
                .thenReturn(2L);

        Map<String, Integer> response = controller.teamCallback(10L, 1L, "callback-token", request);

        assertThat(response).containsEntry("error", 0);
        verify(onlyOfficeCallbackService, never()).reserveSave(anyLong(), anyLong(), anyLong(), anyString(), any(), anyString(), anyString());
        verify(teamTaskService, never()).queueTask(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    private OnlyOfficeCallbackRequest saveRequest() {
        OnlyOfficeCallbackRequest request = new OnlyOfficeCallbackRequest();
        request.setStatus(2);
        request.setKey("doc-key");
        request.setUrl("http://onlyoffice/output.docx");
        return request;
    }
}
