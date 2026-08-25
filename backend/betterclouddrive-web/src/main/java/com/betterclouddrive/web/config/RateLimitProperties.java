package com.betterclouddrive.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "drive.rate-limit")
public class RateLimitProperties {

    /** Default permits per window */
    private int defaultPermits = 300;

    /** Default window in seconds */
    private int defaultPeriodSeconds = 60;

    /** Permits per window for anonymous authentication endpoints */
    private int authPermits = 30;

    /** Window in seconds for anonymous authentication endpoints */
    private int authPeriodSeconds = 60;

    /** Permits per window for upload control endpoints */
    private int uploadPermits = 120;

    /** Window in seconds for upload control endpoints */
    private int uploadPeriodSeconds = 60;

    /** Permits per window for upload chunk endpoints */
    private int uploadChunkPermits = 600;

    /** Window in seconds for upload chunk endpoints */
    private int uploadChunkPeriodSeconds = 60;
}
