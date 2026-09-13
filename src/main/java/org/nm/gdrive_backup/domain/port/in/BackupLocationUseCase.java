package org.nm.gdrive_backup.domain.port.in;

import java.nio.file.Path;

import org.nm.gdrive_backup.domain.model.BackupLocations;
import org.nm.gdrive_backup.domain.model.LocationValidation;

/** Lets the admin inspect and change the backup destination and history database for the session. */
public interface BackupLocationUseCase {

	BackupLocations currentLocations();

	LocationValidation validateBackupDestination(Path destination);

	LocationValidation validateDatabaseFile(Path databaseFile);

	BackupLocations changeBackupDestination(Path destination);

	BackupLocations changeDatabaseFile(Path databaseFile);
}
