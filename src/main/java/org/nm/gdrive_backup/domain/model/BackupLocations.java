package org.nm.gdrive_backup.domain.model;

import java.nio.file.Path;

/** Where backup content is written and which SQLite database holds the backup history. */
public record BackupLocations(Path backupDestination, Path databaseFile) {
}
