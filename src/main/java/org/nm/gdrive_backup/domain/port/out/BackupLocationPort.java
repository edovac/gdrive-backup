package org.nm.gdrive_backup.domain.port.out;

import java.nio.file.Path;

import org.nm.gdrive_backup.domain.model.BackupLocations;
import org.nm.gdrive_backup.domain.model.LocationValidation;

/**
 * Checks and switches the storage locations used by backups. Checks must not create or
 * modify anything; only the apply methods change the active locations.
 */
public interface BackupLocationPort {

	BackupLocations activeLocations();

	LocationValidation checkBackupDestination(Path destination);

	LocationValidation checkDatabaseFile(Path databaseFile);

	void applyBackupDestination(Path destination);

	void applyDatabaseFile(Path databaseFile);
}
