package com.betterclouddrive.service.warehouse;

import com.betterclouddrive.common.exception.WarehouseUnavailableException;
import com.betterclouddrive.service.config.WarehouseQueryProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class WarehouseReportClient {
    private final WarehouseQueryProperties properties;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();

    public WarehouseReportClient(WarehouseQueryProperties properties) { this.properties = properties; }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> query(String sql, Map<String, ?> parameters) {
        if (!properties.isEnabled()) throw new WarehouseUnavailableException();
        if (!properties.getDatabase().matches("[A-Za-z_][A-Za-z0-9_]*")) throw new WarehouseUnavailableException();
        // Limits/JSON formatting are locked in the server-side reader profile.
        // Strict readonly=1 rejects even harmless per-request setting changes.
        StringBuilder query = new StringBuilder("?database=")
                .append(encode(properties.getDatabase()));
        parameters.forEach((key, value) -> query.append("&param_").append(encode(key)).append('=').append(encode(value.toString())));
        try {
            var request = HttpRequest.newBuilder(URI.create(properties.getEndpoint() + query))
                    .timeout(Duration.ofSeconds(8))
                    .header("X-ClickHouse-User", properties.getUser())
                    .header("X-ClickHouse-Key", properties.getPassword())
                    .POST(HttpRequest.BodyPublishers.ofString(sql + " FORMAT JSON", StandardCharsets.UTF_8)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new WarehouseUnavailableException();
            Map<String, Object> result = json.readValue(response.body(), Map.class);
            if (!(result.get("data") instanceof List<?>)) throw new WarehouseUnavailableException();
            return (List<Map<String, Object>>) result.get("data");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new WarehouseUnavailableException();
        } catch (Exception error) {
            throw new WarehouseUnavailableException();
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
