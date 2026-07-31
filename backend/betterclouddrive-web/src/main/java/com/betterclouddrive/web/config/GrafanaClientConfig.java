package com.betterclouddrive.web.config;

import com.betterclouddrive.web.observability.ObservabilityProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class GrafanaClientConfig {

    @Bean
    @Qualifier("grafanaRestClient")
    public RestClient grafanaRestClient(ObservabilityProperties properties) {
        int timeoutMs = Math.max(100, properties.getGrafanaAuth().getLocaleSyncTimeoutMs());
        Duration timeout = Duration.ofMillis(timeoutMs);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(stripTrailingSlash(properties.getGrafanaAuth().getInternalUrl()))
                .requestFactory(requestFactory)
                .build();
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:3000";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
