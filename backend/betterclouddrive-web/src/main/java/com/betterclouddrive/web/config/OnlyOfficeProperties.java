package com.betterclouddrive.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "onlyoffice")
public class OnlyOfficeProperties {

    private String internalUrl = "http://onlyoffice-documentserver";
    private String publicUrl = "http://localhost:8082";
    private String appInternalApiBaseUrl = "http://backend:8080/api/v1";
    private String jwtSecret = "betterclouddrive-onlyoffice-dev-secret";
    private long tokenTtlSeconds = 86400;
    private int callbackDownloadTimeoutSeconds = 30;

    public String normalizedInternalUrl() {
        return trimTrailingSlash(internalUrl);
    }

    public String normalizedPublicUrl() {
        return trimTrailingSlash(publicUrl);
    }

    public String normalizedAppInternalApiBaseUrl() {
        return trimTrailingSlash(appInternalApiBaseUrl);
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().replaceAll("/+$", "");
    }
}
