package com.referboard.profile.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "referboard.storage")
public record StorageProperties(
        @DefaultValue("local") String type,
        @DefaultValue("./.data/resumes") String localDir,
        @DefaultValue S3 s3
) {
    public record S3(String endpoint, @DefaultValue("us-east-1") String region, String bucket, String accessKey,
                     String secretKey, @DefaultValue("true") boolean pathStyle) {
    }
}
