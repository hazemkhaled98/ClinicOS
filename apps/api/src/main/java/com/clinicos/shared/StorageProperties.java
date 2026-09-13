package com.clinicos.shared;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the shared object storage (MinIO). Bound from the
 * {@code clinicos.storage.*} property keys; see {@code application.yml} for
 * the local-development values.
 */
@ConfigurationProperties(prefix = "clinicos.storage")
public record StorageProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket) {
}