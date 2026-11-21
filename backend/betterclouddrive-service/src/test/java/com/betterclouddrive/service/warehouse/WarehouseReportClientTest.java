package com.betterclouddrive.service.warehouse;

import com.betterclouddrive.common.exception.WarehouseUnavailableException;
import com.betterclouddrive.service.config.WarehouseQueryProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class WarehouseReportClientTest {
    @Test void disabledWarehouseIsUnavailableWithoutNetworkDependency() {
        assertThatThrownBy(() -> new WarehouseReportClient(new WarehouseQueryProperties()).query("SELECT 1", Map.of()))
                .isInstanceOf(WarehouseUnavailableException.class);
    }

    @Test void requestUsesBoundParametersAndReaderIdentityWithoutLeakingRemoteErrors() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            assertThat(exchange.getRequestURI().getRawQuery()).contains("database=bcd_warehouse", "param_team=12")
                    .doesNotContain("password", "readonly", "max_execution_time");
            assertThat(exchange.getRequestHeaders().getFirst("X-ClickHouse-User")).isEqualTo("warehouse_reader");
            byte[] body = "controlled remote private error".getBytes();
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var properties = new WarehouseQueryProperties();
            properties.setEnabled(true);
            properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
            assertThatThrownBy(() -> new WarehouseReportClient(properties).query("SELECT {team:Int64}", Map.of("team", 12)))
                    .isInstanceOf(WarehouseUnavailableException.class).hasMessage("Warehouse report unavailable");
        } finally { server.stop(0); }
    }
}
