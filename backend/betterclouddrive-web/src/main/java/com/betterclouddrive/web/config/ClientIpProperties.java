package com.betterclouddrive.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "drive.client-ip")
public class ClientIpProperties {
    private List<String> trustedProxyCidrs = new ArrayList<>();
}
