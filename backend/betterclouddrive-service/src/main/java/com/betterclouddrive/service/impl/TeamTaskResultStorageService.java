package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.service.config.TeamTaskProperties;
import com.betterclouddrive.service.dto.TeamTaskDownload;
import com.betterclouddrive.service.dto.TeamTaskResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.BufferedWriter;
import java.io.Writer;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TeamTaskResultStorageService {

    private static final String AUDIT_EXPORT_DIRECTORY = "audit-exports";

    private final TeamTaskProperties properties;

    public TeamTaskResult saveAuditExport(Long taskId, String fileName, String body) {
        if (taskId == null) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Task id is required");
        }
        String safeName = sanitizeFileName(fileName);
        String relativePath = AUDIT_EXPORT_DIRECTORY + "/" + taskId + "-" + safeName;
        Path path = resolve(relativePath);
        try {
            Files.createDirectories(path.getParent());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes);
            return new TeamTaskResult(
                    relativePath,
                    safeName,
                    "text/csv;charset=UTF-8",
                    bytes.length,
                    LocalDateTime.now().plusHours(Math.max(1, properties.getResultRetentionHours())));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store team task result", e);
        }
    }

    public TeamTaskResult saveAuditExport(Long taskId, String fileName, AuditExportWriter writerAction) {
        if (taskId == null) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Task id is required");
        }
        String safeName = sanitizeFileName(fileName);
        String relativePath = AUDIT_EXPORT_DIRECTORY + "/" + taskId + "-" + safeName;
        Path path = resolve(relativePath);
        Path partial = path.resolveSibling(path.getFileName() + ".part");
        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) {
                writerAction.write(writer);
            }
            Files.move(partial, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return new TeamTaskResult(relativePath, safeName, "text/csv;charset=UTF-8", Files.size(path),
                    LocalDateTime.now().plusHours(Math.max(1, properties.getResultRetentionHours())));
        } catch (RuntimeException | IOException e) {
            try {
                Files.deleteIfExists(partial);
            } catch (IOException ignored) {
                // Preserve the original export failure.
            }
            throw new IllegalStateException("Failed to store team task result", e);
        }
    }

    @FunctionalInterface
    public interface AuditExportWriter {
        void write(Writer writer) throws IOException;
    }

    public TeamTaskDownload open(TeamTaskEntity task) {
        if (!StringUtils.hasText(task.getResultPath())) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Task result is not available");
        }
        Path path = resolve(task.getResultPath());
        if (!Files.exists(path)) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Task result file is missing");
        }
        try {
            return new TeamTaskDownload(
                    task.getResultFileName(),
                    StringUtils.hasText(task.getResultContentType()) ? task.getResultContentType() : "application/octet-stream",
                    task.getResultSizeBytes() == null ? Files.size(path) : task.getResultSizeBytes(),
                    Files.newInputStream(path));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open team task result", e);
        }
    }

    public boolean delete(TeamTaskEntity task) {
        if (task == null || !StringUtils.hasText(task.getResultPath())) {
            return false;
        }
        try {
            return Files.deleteIfExists(resolve(task.getResultPath()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete team task result", e);
        }
    }

    private Path resolve(String relativePath) {
        Path root = Path.of(properties.getResultDirectory()).toAbsolutePath().normalize();
        Path path = root.resolve(relativePath).normalize();
        if (!path.startsWith(root)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Invalid task result path");
        }
        return path;
    }

    private String sanitizeFileName(String fileName) {
        String value = StringUtils.hasText(fileName) ? fileName.trim() : "task-result.csv";
        return value.replaceAll("[\\\\/:*?\"<>|]+", "_");
    }
}
