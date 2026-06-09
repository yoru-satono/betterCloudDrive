package com.betterclouddrive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BetterCloudDriveApplication {

    public static void main(String[] args) {
        SpringApplication.run(BetterCloudDriveApplication.class, args);
    }
}
