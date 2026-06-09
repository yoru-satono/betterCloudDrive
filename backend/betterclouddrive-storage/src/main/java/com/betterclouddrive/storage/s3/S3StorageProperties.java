package com.betterclouddrive.storage.s3;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "storage.s3")
public class S3StorageProperties {
    private String endpoint = "http://localhost:8333";
    private String accessKey = "any";
    private String secretKey = "any";
    private String bucket = "cloud-drive";
    private String region = "us-east-1";
    private boolean pathStyleAccess = true;
}
