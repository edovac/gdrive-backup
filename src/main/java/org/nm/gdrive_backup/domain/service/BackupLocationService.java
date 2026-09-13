package org.nm.gdrive_backup.domain.service;

import java.nio.file.Path;

import org.nm.gdrive_backup.domain.model.BackupLocations;
import org.nm.gdrive_backup.domain.model.LocationStatus;
import org.nm.gdrive_backup.domain.model.LocationValidation;
import org.nm.gdrive_backup.domain.port.in.BackupLocationUseCase;
import org.nm.gdrive_backup.domain.port.out.BackupLocationPort;

/** Validates and applies session-scoped changes to the backup destination and history database. */
public class BackupLocationService implements BackupLocationUseCase {

	private final BackupLocationPort locationPort;
	private final BackupActivity backupActivity;

	public BackupLocationService(BackupLocationPort locationPort, BackupActivity backupActivity) {
		this.locationPort = locationPort;
		this.backupActivity = backupActivity;
	}

	@Override
	public BackupLocations currentLocations() {
		return locationPort.activeLocations();
	}

	@Override
	public LocationValidation validateBackupDestination(Path destination) {
		Path target = normalize(destination);
		if (target.equals(normalize(currentLocations().backupDestination()))) {
			return new LocationValidation(LocationStatus.UNCHANGED, "This is already the backup destination");
		}
		return locationPort.checkBackupDestination(target);
	}

	@Override
	public LocationValidation validateDatabaseFile(Path databaseFile) {
		Path target = normalize(databaseFile);
		if (target.equals(normalize(currentLocations().databaseFile()))) {
			return new LocationValidation(LocationStatus.UNCHANGED, "This is already the backup history database");
		}
		return locationPort.checkDatabaseFile(target);
	}

	@Override
	public BackupLocations changeBackupDestination(Path destination) {
		Path target = normalize(destination);
		backupActivity.changeLocations(() -> {
			if (requiresChange(validateBackupDestination(target))) {
				locationPort.applyBackupDestination(target);
			}
		});
		return currentLocations();
	}

	@Override
	public BackupLocations changeDatabaseFile(Path databaseFile) {
		Path target = normalize(databaseFile);
		backupActivity.changeLocations(() -> {
			if (requiresChange(validateDatabaseFile(target))) {
				locationPort.applyDatabaseFile(target);
			}
		});
		return currentLocations();
	}

	private static boolean requiresChange(LocationValidation validation) {
		if (validation.status() == LocationStatus.INVALID) {
			throw new IllegalArgumentException(validation.detail());
		}
		return validation.status() != LocationStatus.UNCHANGED;
	}

	private static Path normalize(Path path) {
		if (path == null) {
			throw new IllegalArgumentException("A location path is required");
		}
		return path.toAbsolutePath().normalize();
	}
}
