package org.nm.gdrive_backup.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "google.service-account")
public record ServiceAccountProperties(String key, String projectId) {
}