package org.nm.gdrive_backup.domain.port.in;

import java.nio.file.Path;

import org.nm.gdrive_backup.domain.model.BackupLocation;
import org.nm.gdrive_backup.domain.model.LocationValidation;

/** Lets the admin inspect and change the single backup root folder for the session. */
public interface BackupLocationUseCase {

	BackupLocation currentLocation();

	LocationValidation validateRoot(Path root);

	BackupLocation changeRoot(Path root);
}
