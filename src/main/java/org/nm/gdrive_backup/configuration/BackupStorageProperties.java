package org.nm.gdrive_backup.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "google.backup")
public record BackupStorageProperties(String root) {
}