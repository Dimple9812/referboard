package com.referboard.profile.storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

@Configuration(proxyBeanMethods = false)
public class StorageConfig {

    @Bean
    public FileStorage fileStorage(StorageProperties props) {
        return switch (props.type().toLowerCase(Locale.ROOT)) {
            case "local" -> new LocalFileStorage(Path.of(props.localDir()));
            case "s3" -> s3Storage(props.s3());
            default -> throw new IllegalStateException("Unknown referboard.storage.type: " + props.type());
        };
    }

    private static FileStorage s3Storage(StorageProperties.S3 cfg) {
        if (cfg.bucket() == null || cfg.bucket().isBlank()) {
            throw new IllegalStateException("S3_BUCKET must be set when STORAGE_TYPE=s3");
        }
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(cfg.region()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(cfg.pathStyle()).build());
        boolean customEndpoint = cfg.endpoint() != null && !cfg.endpoint().isBlank();
        if (customEndpoint) {
            builder.endpointOverride(URI.create(cfg.endpoint()));
        }
        if (cfg.accessKey() != null && !cfg.accessKey().isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(cfg.accessKey(), cfg.secretKey())));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        // SSE header is AWS-specific; MinIO without KMS rejects it.
        return new S3FileStorage(builder.build(), cfg.bucket(), !customEndpoint);
    }
}
