package org.nm.gdrive_backup.domain.port.in;

import java.util.List;

import org.nm.gdrive_backup.domain.model.BackupMode;
import org.nm.gdrive_backup.domain.model.BackupResult;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;

public interface DriveBackupUseCase {

	BackupResult synchronize(ServiceAccountAccess access, String scopeKey, BackupMode mode);

	/** Backs up the impersonated user's own scope plus every Shared Drive they can see. */
	List<BackupResult> synchronizeVisibleScopes(ServiceAccountAccess access, BackupMode mode);
}
