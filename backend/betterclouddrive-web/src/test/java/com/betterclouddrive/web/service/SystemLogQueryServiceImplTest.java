package com.betterclouddrive.web.service;

import com.betterclouddrive.web.dto.response.SystemLogEntryResponse;
import com.betterclouddrive.web.observability.ObservabilityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SystemLogQueryServiceImplTest {

    @TempDir
    Path tempDir;

    private ObservabilityProperties properties;
    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer server;
    private SystemLogQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new ObservabilityProperties();
        properties.getSystemLogs().setLokiBaseUrl("http://loki.test");
        properties.getSystemLogs().setGrafanaBaseUrl("/grafana");
        restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        service = new SystemLogQueryServiceImpl(properties, restClientBuilder);
    }

    @Test
    void shouldParseRuntimeLogsAndBuildGrafanaTraceLink() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("http://loki.test/loki/api/v1/query_range?")))
                .andRespond(withSuccess("""
                        {
                          "status": "success",
                          "data": {
                            "resultType": "streams",
                            "result": [
                              {
                                "stream": { "job": "better-cloud-drive", "service": "backend", "log_type": "runtime" },
                                "values": [
                                  [
                                    "1781670000123456789",
                                    "{\\"@timestamp\\":\\"2026-06-17T04:20:00.123Z\\",\\"level\\":\\"INFO\\",\\"logger_name\\":\\"com.example.Service\\",\\"message\\":\\"request completed\\",\\"trace_id\\":\\"4bf92f3577b34da6a3ce929d0e0e4736\\",\\"request_id\\":\\"req-1\\",\\"path\\":\\"/api/v1/files\\",\\"method\\":\\"GET\\"}"
                                  ]
                                ]
                              }
                            ]
                          }
                        }
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        List<SystemLogEntryResponse> logs = service.listSystemLogs(
                "4bf92f3577b34da6a3ce929d0e0e4736",
                null,
                null,
                null,
                null,
                Instant.parse("2026-06-17T04:00:00Z"),
                Instant.parse("2026-06-17T05:00:00Z"),
                20);

        assertThat(logs).hasSize(1);
        SystemLogEntryResponse log = logs.getFirst();
        assertThat(log.getId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(log.getIdType()).isEqualTo("traceId");
        assertThat(log.getRequestId()).isEqualTo("req-1");
        assertThat(log.getLogger()).isEqualTo("com.example.Service");
        assertThat(log.getGrafanaUrl()).startsWith("/grafana/explore?orgId=1&left=");
        assertThat(java.net.URLDecoder.decode(log.getGrafanaUrl(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("Loki")
                .contains("4bf92f3577b34da6a3ce929d0e0e4736");
        server.verify();
    }

    @Test
    void shouldFallbackToRequestIdAndFilterLevel() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("http://loki.test/loki/api/v1/query_range?")))
                .andRespond(withSuccess("""
                        {
                          "status": "success",
                          "data": {
                            "result": [
                              {
                                "stream": { "log_type": "runtime" },
                                "values": [
                                  [
                                    "1781670000123456789",
                                    "{\\"level\\":\\"DEBUG\\",\\"message\\":\\"ignored\\",\\"requestId\\":\\"req-2\\"}"
                                  ],
                                  [
                                    "1781670001123456789",
                                    "{\\"level\\":\\"WARN\\",\\"message\\":\\"kept\\",\\"requestId\\":\\"req-2\\"}"
                                  ]
                                ]
                              }
                            ]
                          }
                        }
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        List<SystemLogEntryResponse> logs = service.listSystemLogs(
                null,
                "req-2",
                "WARN",
                null,
                null,
                Instant.parse("2026-06-17T04:00:00Z"),
                Instant.parse("2026-06-17T05:00:00Z"),
                20);

        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst().getId()).isEqualTo("req-2");
        assertThat(logs.getFirst().getIdType()).isEqualTo("requestId");
        assertThat(logs.get(0).getMessage()).isEqualTo("kept");
        server.verify();
    }

    @Test
    void shouldFallbackToLocalRuntimeLogWhenLokiReturnsNoEntries() throws Exception {
        ReflectionTestUtils.setField(service, "logDirectory", tempDir.toString());
        Files.writeString(tempDir.resolve("runtime.log"), """
                {"@timestamp":"2026-06-17T04:20:00.123Z","level":"INFO","logger_name":"com.example.Local","message":"local runtime log","traceId":"local-trace","requestId":"local-request","path":"/api/v1/admin/system-logs","method":"GET"}
                {"@timestamp":"2026-06-17T04:21:00.123Z","level":"WARN","logger_name":"com.example.Local","message":"matching local log","traceId":"local-trace-2","requestId":"local-request-2","path":"/api/v1/files","method":"POST"}
                """);
        server.expect(requestTo(org.hamcrest.Matchers.containsString("http://loki.test/loki/api/v1/query_range?")))
                .andRespond(withSuccess("""
                        {
                          "status": "success",
                          "data": { "result": [] }
                        }
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        List<SystemLogEntryResponse> logs = service.listSystemLogs(
                null,
                null,
                "WARN",
                null,
                "matching",
                Instant.parse("2026-06-17T04:00:00Z"),
                Instant.parse("2026-06-17T05:00:00Z"),
                20);

        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst().getId()).isEqualTo("local-trace-2");
        assertThat(logs.getFirst().getLevel()).isEqualTo("WARN");
        assertThat(logs.getFirst().getLogger()).isEqualTo("com.example.Local");
        assertThat(logs.getFirst().getMessage()).isEqualTo("matching local log");
        server.verify();
    }
}
