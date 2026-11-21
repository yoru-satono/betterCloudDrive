package com.betterclouddrive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BetterCloudDriveApplication {

    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--quota-cutover")) {
            if (!java.util.Arrays.asList(args).contains("--writers-stopped")) {
                throw new IllegalArgumentException("quota_cutover_requires_writers_stopped");
            }
            try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(
                    com.betterclouddrive.maintenance.QuotaCutoverConfiguration.class)
                    .web(org.springframework.boot.WebApplicationType.NONE)
                    .run("--drive.quota.cutover=true")) {
                context.getBean(com.betterclouddrive.service.quota.QuotaCutoverService.class).run(true);
                System.out.println("quota_cutover_complete");
            }
            return;
        }
        SpringApplication.run(BetterCloudDriveApplication.class, args);
    }
}
