package org.nm.gdrive_backup.domain.service;

import java.nio.file.Path;

import org.nm.gdrive_backup.domain.model.BackupLocation;
import org.nm.gdrive_backup.domain.model.LocationStatus;
import org.nm.gdrive_backup.domain.model.LocationValidation;
import org.nm.gdrive_backup.domain.port.in.BackupLocationUseCase;
import org.nm.gdrive_backup.domain.port.out.BackupLocationPort;

/** Validates and applies session-scoped changes to the single backup root folder. */
public class BackupLocationService implements BackupLocationUseCase {

	private final BackupLocationPort locationPort;
	private final BackupActivity backupActivity;

	public BackupLocationService(BackupLocationPort locationPort, BackupActivity backupActivity) {
		this.locationPort = locationPort;
		this.backupActivity = backupActivity;
	}

	@Override
	public BackupLocation currentLocation() {
		return locationPort.activeLocation();
	}

	@Override
	public LocationValidation validateRoot(Path root) {
		Path target = normalize(root);
		if (target.equals(normalize(currentLocation().root()))) {
			return new LocationValidation(LocationStatus.UNCHANGED, "This is already the backup location");
		}
		return locationPort.checkRoot(target);
	}

	@Override
	public BackupLocation changeRoot(Path root) {
		Path target = normalize(root);
		backupActivity.changeLocations(() -> {
			if (requiresChange(validateRoot(target))) {
				locationPort.applyRoot(target);
			}
		});
		return currentLocation();
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
