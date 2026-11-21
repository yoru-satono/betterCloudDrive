package com.betterclouddrive.service.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "warehouse.query")
public class WarehouseQueryProperties {
    private boolean enabled = false;
    private String endpoint = "http://clickhouse:8123";
    private String database = "bcd_warehouse";
    private String user = "warehouse_reader";
    private String password = "";
}
