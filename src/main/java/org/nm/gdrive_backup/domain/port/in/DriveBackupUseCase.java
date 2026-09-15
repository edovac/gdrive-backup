package org.nm.gdrive_backup.domain.port.in;

import java.util.List;

import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.BackupMode;
import org.nm.gdrive_backup.domain.model.BackupResult;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;

public interface DriveBackupUseCase {

	BackupResult synchronize(ServiceAccountAccess access, DriveScope scope, BackupMode mode);

	/** Backs up exactly the drives the admin selected — the personal drive and/or specific Shared Drives. */
	List<BackupResult> synchronizeSelectedDrives(ServiceAccountAccess access, List<AvailableDrive> selectedDrives,
			BackupMode mode);
}
