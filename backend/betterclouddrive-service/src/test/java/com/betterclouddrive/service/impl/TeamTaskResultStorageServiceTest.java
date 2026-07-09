package com.betterclouddrive.service.impl;

import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.service.config.TeamTaskProperties;
import com.betterclouddrive.service.dto.TeamTaskDownload;
import com.betterclouddrive.service.dto.TeamTaskResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TeamTaskResultStorageServiceTest {

    @TempDir
    Path tempDir;

    private TeamTaskResultStorageService service;

    @BeforeEach
    void setUp() {
        TeamTaskProperties properties = new TeamTaskProperties();
        properties.setResultDirectory(tempDir.toString());
        properties.setResultRetentionHours(2);
        service = new TeamTaskResultStorageService(properties);
    }

    @Test
    void saveOpenAndDeleteAuditExport_shouldUseConfiguredResultDirectory() throws Exception {
        TeamTaskResult result = service.saveAuditExport(77L, "team-10-audit.csv", "a,b\n1,2\n");
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(77L)
                .resultPath(result.path())
                .resultFileName(result.fileName())
                .resultContentType(result.contentType())
                .resultSizeBytes(result.sizeBytes())
                .resultExpiresAt(result.expiresAt())
                .build();

        TeamTaskDownload download = service.open(task);
        String body;
        try (var inputStream = download.inputStream()) {
            body = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(result.path()).startsWith("audit-exports/");
        assertThat(result.fileName()).isEqualTo("team-10-audit.csv");
        assertThat(result.sizeBytes()).isEqualTo("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8).length);
        assertThat(result.expiresAt()).isNotNull();
        assertThat(download.fileName()).isEqualTo("team-10-audit.csv");
        assertThat(download.contentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(body).isEqualTo("a,b\n1,2\n");
        assertThat(service.delete(task)).isTrue();
    }
}
