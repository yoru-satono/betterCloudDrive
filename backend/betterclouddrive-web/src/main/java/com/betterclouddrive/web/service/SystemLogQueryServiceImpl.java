package com.betterclouddrive.web.service;

import com.betterclouddrive.web.dto.response.SystemLogEntryResponse;
import com.betterclouddrive.web.observability.ObservabilityProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemLogQueryServiceImpl implements SystemLogQueryService {

    private static final Duration DEFAULT_RANGE = Duration.ofHours(24);
    private static final Duration GRAFANA_LINK_RANGE = Duration.ofMinutes(5);
    private static final String DEFAULT_LOG_TYPE = "runtime";
    private static final int LOCAL_LOG_FILE_LIMIT = 3;

    private final ObservabilityProperties properties;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${logging.file.path:./logs}")
    private String logDirectory = "./logs";

    @Override
    public List<SystemLogEntryResponse> listSystemLogs(String traceId, String requestId, String level, String logType,
                                                       String keyword, Instant startTime, Instant endTime,
                                                       Integer limit) {
        Instant end = endTime != null ? endTime : Instant.now();
        Instant start = startTime != null ? startTime : end.minus(DEFAULT_RANGE);
        int sanitizedLimit = sanitizeLimit(limit);
        String normalizedLogType = normalizeLogType(logType);
        String query = buildLokiQuery(normalizedLogType, traceId, requestId, keyword);
        URI uri = UriComponentsBuilder
                .fromUriString(stripTrailingSlash(properties.getSystemLogs().getLokiBaseUrl()))
                .path("/loki/api/v1/query_range")
                .queryParam("query", query)
                .queryParam("start", toEpochNanos(start))
                .queryParam("end", toEpochNanos(end))
                .queryParam("limit", sanitizedLimit)
                .build()
                .encode()
                .toUri();

        List<SystemLogEntryResponse> entries = new ArrayList<>();
        try {
            String responseBody = restClientBuilder.build()
                    .get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);

            JsonNode response = parseLokiResponseBody(responseBody);
            entries = parseLokiResponse(response, normalizedLogType, level, sanitizedLimit);
        } catch (Exception e) {
            log.warn("Failed to query Loki system logs: {}", e.getMessage());
        }
        if (entries.isEmpty()) {
            entries = readLocalLogFallback(normalizedLogType, traceId, requestId, level, keyword, start, end, sanitizedLimit);
        }
        entries.sort(Comparator.comparing(SystemLogEntryResponse::getTimestamp,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return entries.size() > sanitizedLimit ? entries.subList(0, sanitizedLimit) : entries;
    }

    private JsonNode parseLokiResponseBody(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(responseBody);
        } catch (Exception e) {
            log.warn("Failed to parse Loki response: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private int sanitizeLimit(Integer limit) {
        int defaultLimit = properties.getSystemLogs().getDefaultLimit();
        int maxLimit = properties.getSystemLogs().getMaxLimit();
        int value = limit != null ? limit : defaultLimit;
        if (value < 1) {
            return defaultLimit;
        }
        return Math.min(value, maxLimit);
    }

    private String normalizeLogType(String logType) {
        if (!StringUtils.hasText(logType)) {
            return DEFAULT_LOG_TYPE;
        }
        String normalized = logType.trim().toLowerCase(Locale.ROOT);
        return "audit".equals(normalized) ? "audit" : DEFAULT_LOG_TYPE;
    }

    private String buildLokiQuery(String logType, String traceId, String requestId, String keyword) {
        StringBuilder query = new StringBuilder("{job=\"better-cloud-drive\",service=\"backend\",log_type=\"")
                .append(escapeLabelValue(logType))
                .append("\"}");
        appendLineFilter(query, traceId);
        appendLineFilter(query, requestId);
        appendLineFilter(query, keyword);
        return query.toString();
    }

    private void appendLineFilter(StringBuilder query, String value) {
        if (StringUtils.hasText(value)) {
            query.append(" |= \"").append(escapeLogqlString(value.trim())).append("\"");
        }
    }

    private List<SystemLogEntryResponse> parseLokiResponse(JsonNode response, String requestedLogType,
                                                           String levelFilter, int limit) {
        List<SystemLogEntryResponse> entries = new ArrayList<>();
        JsonNode streams = response == null ? null : response.at("/data/result");
        if (streams == null || !streams.isArray()) {
            return entries;
        }
        for (JsonNode stream : streams) {
            String streamLogType = text(stream.at("/stream/log_type"), requestedLogType);
            JsonNode values = stream.get("values");
            if (values == null || !values.isArray()) {
                continue;
            }
            for (JsonNode value : values) {
                if (!value.isArray() || value.size() < 2) {
                    continue;
                }
                SystemLogEntryResponse entry = toEntry(value.get(0).asText(), value.get(1).asText(), streamLogType);
                if (matchesLevel(entry, levelFilter)) {
                    entries.add(entry);
                }
                if (entries.size() >= limit) {
                    return entries;
                }
            }
        }
        return entries;
    }

    private List<SystemLogEntryResponse> readLocalLogFallback(String logType, String traceId, String requestId,
                                                              String levelFilter, String keyword, Instant start,
                                                              Instant end, int limit) {
        List<Path> files = localLogFiles(logType);
        if (files.isEmpty()) {
            return List.of();
        }
        int maxLines = Math.min(10_000, Math.max(1_000, limit * 20));
        ArrayDeque<String> lines = new ArrayDeque<>(maxLines);
        for (Path file : files) {
            try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
                stream.forEach(line -> {
                    if (lines.size() == maxLines) {
                        lines.removeFirst();
                    }
                    lines.addLast(line);
                });
            } catch (IOException e) {
                log.debug("Failed to read local log file {}: {}", file, e.getMessage());
            }
        }

        List<String> orderedLines = new ArrayList<>(lines);
        List<SystemLogEntryResponse> entries = new ArrayList<>();
        String fallbackNanos = toEpochNanos(Instant.now());
        for (int i = orderedLines.size() - 1; i >= 0; i--) {
            String line = orderedLines.get(i);
            if (!lineMatches(line, traceId) || !lineMatches(line, requestId) || !lineMatches(line, keyword)) {
                continue;
            }
            SystemLogEntryResponse entry = toEntry(fallbackNanos, line, logType);
            if (!matchesLevel(entry, levelFilter) || !withinRange(entry.getTimestamp(), start, end)) {
                continue;
            }
            entries.add(entry);
            if (entries.size() >= limit) {
                break;
            }
        }
        return entries;
    }

    private List<Path> localLogFiles(String logType) {
        Path directory = Path.of(logDirectory);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        String prefix = "audit".equals(logType) ? "audit.log" : "runtime.log";
        try (Stream<Path> paths = Files.list(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(prefix) && !name.endsWith(".gz");
                    })
                    .sorted(Comparator.comparing(this::lastModified).reversed())
                    .limit(LOCAL_LOG_FILE_LIMIT)
                    .sorted(Comparator.comparing(this::lastModified))
                    .toList();
        } catch (IOException e) {
            log.debug("Failed to list local log directory {}: {}", directory, e.getMessage());
            return List.of();
        }
    }

    private Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private boolean lineMatches(String line, String value) {
        return !StringUtils.hasText(value) || line.contains(value.trim());
    }

    private boolean withinRange(String timestamp, Instant start, Instant end) {
        Instant instant = parseInstant(timestamp);
        return !instant.isBefore(start) && !instant.isAfter(end);
    }

    private SystemLogEntryResponse toEntry(String lokiTimestampNanos, String line, String logType) {
        JsonNode json = parseLogLine(line);
        String timestamp = timestamp(json, lokiTimestampNanos);
        String traceId = firstText(json, "traceId", "trace_id");
        String requestId = firstText(json, "requestId", "request_id");
        IdChoice id = chooseId(traceId, requestId, lokiTimestampNanos);
        String level = firstText(json, "level");
        String logger = firstText(json, "logger_name", "logger");
        String message = firstText(json, "message");
        String path = firstText(json, "path");
        String method = firstText(json, "method");

        return SystemLogEntryResponse.builder()
                .id(id.id())
                .idType(id.type())
                .traceId(traceId)
                .requestId(requestId)
                .timestamp(timestamp)
                .level(level)
                .logger(logger)
                .message(StringUtils.hasText(message) ? message : line)
                .path(path)
                .method(method)
                .logType(logType)
                .grafanaUrl(buildGrafanaUrl(id, logType, timestamp, message))
                .build();
    }

    private JsonNode parseLogLine(String line) {
        if (!StringUtils.hasText(line)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(line);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private boolean matchesLevel(SystemLogEntryResponse entry, String levelFilter) {
        if (!StringUtils.hasText(levelFilter)) {
            return true;
        }
        return levelFilter.trim().equalsIgnoreCase(entry.getLevel());
    }

    private String timestamp(JsonNode json, String lokiTimestampNanos) {
        String timestamp = firstText(json, "@timestamp", "timestamp");
        if (StringUtils.hasText(timestamp)) {
            return timestamp;
        }
        try {
            long nanos = Long.parseLong(lokiTimestampNanos);
            return Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L).toString();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private IdChoice chooseId(String traceId, String requestId, String lokiTimestampNanos) {
        if (StringUtils.hasText(traceId)) {
            return new IdChoice(traceId, "traceId");
        }
        if (StringUtils.hasText(requestId)) {
            return new IdChoice(requestId, "requestId");
        }
        return new IdChoice(lokiTimestampNanos, "timestamp");
    }

    private String buildGrafanaUrl(IdChoice id, String logType, String timestamp, String message) {
        String query = "{job=\"better-cloud-drive\",service=\"backend\",log_type=\"" + escapeLabelValue(logType) + "\"}";
        if ("traceId".equals(id.type()) || "requestId".equals(id.type())) {
            query += " |= \"" + escapeLogqlString(id.id()) + "\"";
        } else if (StringUtils.hasText(message)) {
            query += " |= \"" + escapeLogqlString(message.substring(0, Math.min(message.length(), 80))) + "\"";
        }

        ObjectNode left = objectMapper.createObjectNode();
        String datasource = properties.getSystemLogs().getGrafanaDatasource();
        left.put("datasource", datasource);
        ArrayNode queries = left.putArray("queries");
        ObjectNode grafanaQuery = queries.addObject();
        grafanaQuery.put("refId", "A");
        grafanaQuery.put("datasource", datasource);
        grafanaQuery.put("expr", query);
        ObjectNode range = left.putObject("range");
        Instant center = parseInstant(timestamp);
        range.put("from", center.minus(GRAFANA_LINK_RANGE).toString());
        range.put("to", center.plus(GRAFANA_LINK_RANGE).toString());

        String encoded = URLEncoder.encode(left.toString(), StandardCharsets.UTF_8);
        return stripTrailingSlash(properties.getSystemLogs().getGrafanaBaseUrl())
                + "/explore?orgId=1&left=" + encoded;
    }

    private Instant parseInstant(String timestamp) {
        if (StringUtils.hasText(timestamp)) {
            try {
                return Instant.parse(timestamp);
            } catch (Exception e) {
                log.debug("Failed to parse system log timestamp {}", timestamp);
            }
        }
        return Instant.now();
    }

    private String firstText(JsonNode json, String... names) {
        if (json == null) {
            return null;
        }
        for (String name : names) {
            String value = text(json.get(name), null);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        return node.asText();
    }

    private String escapeLogqlString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String escapeLabelValue(String value) {
        return escapeLogqlString(value);
    }

    private String stripTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String toEpochNanos(Instant instant) {
        long nanos = Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
        return Long.toString(nanos);
    }

    private record IdChoice(String id, String type) {
    }
}
