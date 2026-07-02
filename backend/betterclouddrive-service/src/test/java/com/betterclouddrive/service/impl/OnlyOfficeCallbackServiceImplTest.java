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
import static org.mockito.ArgumentMatchers.eq;
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
        when(onlyOfficeCallbackRepository.insertProcessing(10L, 1L, "doc-key", 2, "url-hash"))
                .thenReturn(0);

        boolean reserved = onlyOfficeCallbackService.reserveSave(10L, 1L, "doc-key", 2, "url-hash");

        assertThat(reserved).isFalse();
    }

    @Test
    void markSaved_shouldUpdateSavedState() {
        onlyOfficeCallbackService.markSaved(10L, 1L, "doc-key", 2, "url-hash", 99L);

        verify(onlyOfficeCallbackRepository).markSaved(
                eq(10L), eq(1L), eq("doc-key"), eq(2), eq("url-hash"), eq(99L), any());
    }
}
