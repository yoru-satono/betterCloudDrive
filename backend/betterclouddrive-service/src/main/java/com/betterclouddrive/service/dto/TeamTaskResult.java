package com.betterclouddrive.service.dto;

import java.time.LocalDateTime;

public record TeamTaskResult(
        String path,
        String fileName,
        String contentType,
        long sizeBytes,
        LocalDateTime expiresAt) {
}
