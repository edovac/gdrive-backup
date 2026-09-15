package org.nm.gdrive_backup.domain.port.out;

import java.nio.file.Path;

import org.nm.gdrive_backup.domain.model.BackupLocation;
import org.nm.gdrive_backup.domain.model.LocationValidation;

/**
 * Checks and switches the single root folder used by backups. Checking must not create or
 * modify anything; only {@code applyRoot} changes the active location.
 */
public interface BackupLocationPort {

	BackupLocation activeLocation();

	LocationValidation checkRoot(Path root);

	void applyRoot(Path root);
}
