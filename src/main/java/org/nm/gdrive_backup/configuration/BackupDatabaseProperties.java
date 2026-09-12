package org.nm.gdrive_backup.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gdrive.backup")
public record BackupDatabaseProperties(String database) {
}