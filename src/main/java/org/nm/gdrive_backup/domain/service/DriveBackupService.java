package org.nm.gdrive_backup.domain.service;

import org.nm.gdrive_backup.domain.model.BackupResult;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.port.in.DriveBackupUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveChangeSyncUseCase;
import org.nm.gdrive_backup.domain.port.in.InitialDriveSyncUseCase;
import org.nm.gdrive_backup.domain.port.out.SyncStatePort;

/** Selects the appropriate synchronization flow for a Drive scope. */
public class DriveBackupService implements DriveBackupUseCase {

	private final SyncStatePort syncStatePort;
	private final InitialDriveSyncUseCase initialSyncUseCase;
	private final DriveChangeSyncUseCase changeSyncUseCase;

	public DriveBackupService(SyncStatePort syncStatePort, InitialDriveSyncUseCase initialSyncUseCase,
			DriveChangeSyncUseCase changeSyncUseCase) {
		this.syncStatePort = syncStatePort;
		this.initialSyncUseCase = initialSyncUseCase;
		this.changeSyncUseCase = changeSyncUseCase;
	}

	@Override
	public BackupResult synchronize(ServiceAccountAccess access, String scopeKey) {
		if (syncStatePort.findByScopeKey(scopeKey).isEmpty()) {
			var result = initialSyncUseCase.synchronize(access, scopeKey);
			return new BackupResult(result.scopeKey(), result.fileCount(), true);
		}
		var result = changeSyncUseCase.synchronize(access, scopeKey);
		return new BackupResult(result.scopeKey(), result.changeCount(), false);
	}
}
