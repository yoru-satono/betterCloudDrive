package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.repository.OnlyOfficeCallbackRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnlyOfficeCallbackServiceImplTest {

    @Mock
    private OnlyOfficeCallbackRepository onlyOfficeCallbackRepository;

    @InjectMocks
    private OnlyOfficeCallbackServiceImpl onlyOfficeCallbackService;

    @Test
    void callbackKey_shouldRejectBlankKey() {
        assertThatThrownBy(() -> onlyOfficeCallbackService.callbackKey(" "))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void contentUrlHash_shouldReturnStableSha256() {
        String first = onlyOfficeCallbackService.contentUrlHash(" http://onlyoffice/output.docx ");
        String second = onlyOfficeCallbackService.contentUrlHash("http://onlyoffice/output.docx");

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64);
    }

    @Test
    void reserveSave_shouldReturnFalseForDuplicateRecord() {
        when(onlyOfficeCallbackRepository.insertProcessing(
                10L, 1L, 2L, "doc-key", 2, "url-hash", "http://onlyoffice/output.docx"))
                .thenReturn(0);

        boolean reserved = onlyOfficeCallbackService.reserveSave(
                10L, 1L, 2L, "doc-key", 2, "url-hash", "http://onlyoffice/output.docx");

        assertThat(reserved).isFalse();
    }

    @Test
    void attachTask_shouldIgnoreNullTaskId() {
        onlyOfficeCallbackService.attachTask(10L, 1L, "doc-key", 2, "url-hash", null);

        verify(onlyOfficeCallbackRepository, never()).attachTask(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void markSaved_shouldUpdateSavedStateById() {
        onlyOfficeCallbackService.markSaved(88L, 99L);

        verify(onlyOfficeCallbackRepository).markSavedById(eq(88L), eq(99L), any());
    }

    @Test
    void markFailed_shouldTruncateLongError() {
        onlyOfficeCallbackService.markFailed(88L, "x".repeat(600));

        verify(onlyOfficeCallbackRepository).markFailedById(eq(88L), argThat(message -> message.length() == 512), any());
    }
}
