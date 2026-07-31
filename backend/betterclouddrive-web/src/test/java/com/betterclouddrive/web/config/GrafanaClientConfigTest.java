package com.betterclouddrive.web.config;

import com.betterclouddrive.web.observability.ObservabilityProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GrafanaClientConfigTest {

    @Test
    void configuredClientSupportsPatchRequests() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/user/preferences", exchange -> {
            byte[] response = exchange.getRequestMethod().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ObservabilityProperties properties = new ObservabilityProperties();
            properties.getGrafanaAuth().setInternalUrl(
                    "http://127.0.0.1:" + server.getAddress().getPort());

            String method = new GrafanaClientConfig().grafanaRestClient(properties)
                    .patch()
                    .uri("/api/user/preferences")
                    .body("{}")
                    .retrieve()
                    .body(String.class);

            assertThat(method).isEqualTo("PATCH");
        } finally {
            server.stop(0);
        }
    }
}
