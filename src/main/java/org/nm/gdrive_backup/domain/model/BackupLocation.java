package org.nm.gdrive_backup.domain.model;

import java.nio.file.Path;

/** The single root folder holding the backup history database, the archives, and the capture store. */
public record BackupLocation(Path root) {
}
