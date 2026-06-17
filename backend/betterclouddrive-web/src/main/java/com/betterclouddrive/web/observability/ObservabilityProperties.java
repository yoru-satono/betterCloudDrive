package com.betterclouddrive.web.observability;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {
    private boolean enabled = true;
    private Audit audit = new Audit();
    private SystemLogs systemLogs = new SystemLogs();
    private GrafanaAuth grafanaAuth = new GrafanaAuth();

    @Data
    public static class Audit {
        private boolean enabled = true;
        private boolean fullEventEnabled = true;
        private int maxBodyBytes = 8192;
        private List<String> sensitiveFields = new ArrayList<>(List.of(
                "password", "newPassword", "oldPassword", "token", "accessToken", "refreshToken",
                "authorization", "cookie", "secret", "key", "sharePassword", "passwordCiphertext"
        ));
        private List<String> bodyExcludedPathPrefixes = new ArrayList<>(List.of(
                "/api/v1/upload/",
                "/api/v1/download/",
                "/api/v1/shares/access/",
                "/webdav/"
        ));
    }

    @Data
    public static class SystemLogs {
        private String lokiBaseUrl = "http://localhost:3100";
        private String grafanaBaseUrl = "/grafana";
        private String grafanaDatasource = "Loki";
        private int defaultLimit = 100;
        private int maxLimit = 500;
    }

    @Data
    public static class GrafanaAuth {
        private String cookieName = "BCD_GRAFANA_SESSION";
        private String secret = "";
        private long ttlSeconds = 300;
    }
}
