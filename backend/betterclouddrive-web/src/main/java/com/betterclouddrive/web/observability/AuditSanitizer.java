package com.betterclouddrive.web.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class AuditSanitizer {

    private static final String REDACTED = "***REDACTED***";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ObservabilityProperties properties;

    public Map<String, String> sanitizeHeaders(Map<String, String> headers) {
        Set<String> sensitive = sensitiveFields();
        return headers.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> isSensitive(entry.getKey(), sensitive) ? REDACTED : entry.getValue(),
                        (a, b) -> b,
                        java.util.LinkedHashMap::new));
    }

    public String sanitizeBody(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        int limit = Math.min(body.length, properties.getAudit().getMaxBodyBytes());
        String raw = new String(body, 0, limit, StandardCharsets.UTF_8);
        String sanitized = sanitizeJson(raw);
        if (body.length > limit) {
            return sanitized + "...[truncated]";
        }
        return sanitized;
    }

    private String sanitizeJson(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            redactNode(root, sensitiveFields());
            return objectMapper.writeValueAsString(root);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private void redactNode(JsonNode node, Set<String> sensitive) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (isSensitive(entry.getKey(), sensitive)) {
                    objectNode.put(entry.getKey(), REDACTED);
                } else {
                    redactNode(entry.getValue(), sensitive);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (JsonNode child : arrayNode) {
                redactNode(child, sensitive);
            }
        }
    }

    private boolean isSensitive(String field, Set<String> sensitive) {
        String normalized = field.toLowerCase(Locale.ROOT);
        return sensitive.stream().anyMatch(normalized::contains);
    }

    private Set<String> sensitiveFields() {
        return properties.getAudit().getSensitiveFields().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }
}
