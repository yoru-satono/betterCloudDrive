package com.betterclouddrive.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "drive.rate-limit")
public class RateLimitProperties {

    /** Default permits per window */
    private int defaultPermits = 100;

    /** Default window in seconds */
    private int defaultPeriodSeconds = 60;

    /** Permits per window for upload endpoints */
    private int uploadPermits = 10;

    /** Window in seconds for upload endpoints */
    private int uploadPeriodSeconds = 60;
}
