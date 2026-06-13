package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.OperationLogEntity;
import com.betterclouddrive.dal.repository.OperationLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;

@ExtendWith(MockitoExtension.class)
class OperationLogServiceImplTest {

    @Mock private OperationLogRepository operationLogRepository;
    @InjectMocks private OperationLogServiceImpl operationLogService;

    private OperationLogEntity log(Long userId, String action) {
        return OperationLogEntity.builder().userId(userId).actionType(action).build();
    }

    @Test
    void logAsync_shouldWrapPlainDetailAsJson() {
        operationLogService.logAsync(1L, "UPLOAD", "FILE", null, "UploadController.completeUpload", "127.0.0.1", "test");

        org.mockito.ArgumentCaptor<OperationLogEntity> captor = org.mockito.ArgumentCaptor.forClass(OperationLogEntity.class);
        verify(operationLogRepository).save(captor.capture());
        assertThat(captor.getValue().getDetail()).isEqualTo("{\"message\":\"UploadController.completeUpload\"}");
    }

    @Test
    void listLogs_shouldReturnPageWithAllFilters() {
        Page<OperationLogEntity> page = new PageImpl<>(
                List.of(log(1L, "UPLOAD")), PageRequest.of(0, 20), 1);
        when(operationLogRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(page);

        LocalDateTime start = LocalDateTime.now().minusDays(7);
        LocalDateTime end = LocalDateTime.now();
        PageResult<OperationLogEntity> result = operationLogService.listLogs(1L, "UPLOAD", start, end, 1, 20);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getTotal()).isEqualTo(1);
        verify(operationLogRepository).findAll(any(Specification.class), any(PageRequest.class));
    }

    @Test
    void listLogs_shouldReturnPageWithNoFilters() {
        Page<OperationLogEntity> page = new PageImpl<>(
                List.of(log(1L, "DOWNLOAD"), log(2L, "DELETE")), PageRequest.of(0, 20), 2);
        when(operationLogRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(page);

        PageResult<OperationLogEntity> result = operationLogService.listLogs(null, null, null, null, 1, 20);

        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getTotal()).isEqualTo(2);
    }

    @Test
    void listLogs_shouldFilterByDateRange() {
        Page<OperationLogEntity> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(operationLogRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(page);

        LocalDateTime start = LocalDateTime.of(2025, 1, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2025, 1, 31, 23, 59);
        PageResult<OperationLogEntity> result = operationLogService.listLogs(null, null, start, end, 1, 20);

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isEqualTo(0);
    }
}
