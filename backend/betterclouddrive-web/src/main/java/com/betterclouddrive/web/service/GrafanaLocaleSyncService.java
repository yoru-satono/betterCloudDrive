package com.betterclouddrive.web.service;

import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.service.i18n.LocaleCatalog;
import com.betterclouddrive.web.observability.ObservabilityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Slf4j
@Service
public class GrafanaLocaleSyncService {

    public static final String HEADER_LOCALE = "X-WEBAUTH-LOCALE";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String GRAFANA_ROLE_ADMIN = "Admin";

    private final ObservabilityProperties properties;
    private final LocaleCatalog localeCatalog;
    private final RestClient grafanaRestClient;

    public GrafanaLocaleSyncService(
            ObservabilityProperties properties,
            LocaleCatalog localeCatalog,
            @Qualifier("grafanaRestClient") RestClient grafanaRestClient) {
        this.properties = properties;
        this.localeCatalog = localeCatalog;
        this.grafanaRestClient = grafanaRestClient;
    }

    public void syncIfAdmin(UserEntity user) {
        if (!properties.getGrafanaAuth().isLocaleSyncEnabled() || !isAdmin(user)) {
            return;
        }
        String locale = grafanaLocale(user.getPreferredLocale());
        try {
            RestClient.RequestBodySpec request = grafanaRestClient.patch()
                    .uri("/api/user/preferences")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(GrafanaAuthService.HEADER_USER, safe(user.getUsername()))
                    .header(GrafanaAuthService.HEADER_NAME,
                            StringUtils.hasText(user.getNickname()) ? user.getNickname() : safe(user.getUsername()))
                    .header(GrafanaAuthService.HEADER_ROLE, GRAFANA_ROLE_ADMIN);
            if (StringUtils.hasText(user.getEmail())) {
                request.header(GrafanaAuthService.HEADER_EMAIL, user.getEmail());
            }
            request.body(Map.of("language", locale)).retrieve().toBodilessEntity();
        } catch (RestClientException exception) {
            log.warn("Failed to synchronize Grafana locale: userId={}, locale={}, reason={}",
                    user.getId(), locale, exception.getClass().getSimpleName());
        }
    }

    public String grafanaLocale(String preferredLocale) {
        String locale = localeCatalog.resolveOrDefault(preferredLocale);
        return switch (locale) {
            case "zh-Hans" -> "zh-Hans";
            case "en-US" -> "en-US";
            default -> "en-US";
        };
    }

    private boolean isAdmin(UserEntity user) {
        return user != null && ROLE_ADMIN.equals(user.getRole());
    }

    private String safe(String value) {
        return value != null ? value : "";
    }
}
