package com.betterclouddrive.service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "drive.download.folder-zip")
public class FolderZipDownloadProperties {
    private int maxFiles = 1000;
    private long maxSizeBytes = 1073741824L;
}
