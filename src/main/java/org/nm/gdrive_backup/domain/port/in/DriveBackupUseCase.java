package org.nm.gdrive_backup.domain.port.in;

import org.nm.gdrive_backup.domain.model.BackupMode;
import org.nm.gdrive_backup.domain.model.BackupResult;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;

public interface DriveBackupUseCase {

	BackupResult synchronize(ServiceAccountAccess access, String scopeKey, BackupMode mode);
}
