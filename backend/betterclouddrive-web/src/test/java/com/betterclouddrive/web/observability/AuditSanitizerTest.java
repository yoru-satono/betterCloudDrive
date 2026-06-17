package com.betterclouddrive.web.observability;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

class AuditSanitizerTest {

    private final ObservabilityProperties properties = new ObservabilityProperties();
    private final AuditSanitizer sanitizer = new AuditSanitizer(properties);

    @Test
    void shouldRedactSensitiveHeadersAndJsonFields() {
        Map<String, String> headers = sanitizer.sanitizeHeaders(Map.of(
                "Authorization", "Bearer token",
                "User-Agent", "test"));

        assertThat(headers.get("Authorization")).isEqualTo("***REDACTED***");
        assertThat(headers.get("User-Agent")).isEqualTo("test");

        String body = sanitizer.sanitizeBody("""
                {"username":"alice","password":"secret","profile":{"accessToken":"abc"}}
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(body).contains("\"username\":\"alice\"");
        assertThat(body).contains("\"password\":\"***REDACTED***\"");
        assertThat(body).contains("\"accessToken\":\"***REDACTED***\"");
        assertThat(body).doesNotContain("secret");
        assertThat(body).doesNotContain("abc");
    }

    @Test
    void shouldTruncateLargeBodies() {
        properties.getAudit().setMaxBodyBytes(5);

        String body = sanitizer.sanitizeBody("123456789".getBytes(StandardCharsets.UTF_8));

        assertThat(body).isEqualTo("12345...[truncated]");
    }
}
