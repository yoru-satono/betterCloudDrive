package com.betterclouddrive.service.dto;

import java.io.InputStream;

public record TeamTaskDownload(
        String fileName,
        String contentType,
        long sizeBytes,
        InputStream inputStream) {
}
