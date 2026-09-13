package org.nm.gdrive_backup.domain.service;

import org.nm.gdrive_backup.domain.model.BackupMode;
import org.nm.gdrive_backup.domain.model.BackupResult;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StaleDrivePageTokenException;
import org.nm.gdrive_backup.domain.port.in.DriveBackupUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveChangeSyncUseCase;
import org.nm.gdrive_backup.domain.port.in.InitialDriveSyncUseCase;
import org.nm.gdrive_backup.domain.port.out.SyncStatePort;

/** Selects the appropriate synchronization flow for a Drive scope. */
public class DriveBackupService implements DriveBackupUseCase {

	private final SyncStatePort syncStatePort;
	private final InitialDriveSyncUseCase initialSyncUseCase;
	private final DriveChangeSyncUseCase changeSyncUseCase;
	private final BackupActivity backupActivity;

	public DriveBackupService(SyncStatePort syncStatePort, InitialDriveSyncUseCase initialSyncUseCase,
			DriveChangeSyncUseCase changeSyncUseCase, BackupActivity backupActivity) {
		this.syncStatePort = syncStatePort;
		this.initialSyncUseCase = initialSyncUseCase;
		this.changeSyncUseCase = changeSyncUseCase;
		this.backupActivity = backupActivity;
	}

	@Override
	public BackupResult synchronize(ServiceAccountAccess access, String scopeKey, BackupMode mode) {
		return backupActivity.duringBackup(() -> synchronizeScope(access, scopeKey, mode));
	}

	private BackupResult synchronizeScope(ServiceAccountAccess access, String scopeKey, BackupMode mode) {
		if (mode == BackupMode.FULL) {
			return runFullInventory(access, scopeKey);
		}
		if (syncStatePort.findByScopeKey(scopeKey).isEmpty()) {
			return runFullInventory(access, scopeKey);
		}
		try {
			var result = changeSyncUseCase.synchronize(access, scopeKey);
			return new BackupResult(result.scopeKey(), result.changeCount(), false);
		} catch (StaleDrivePageTokenException exception) {
			return runFullInventory(access, scopeKey);
		}
	}

	private BackupResult runFullInventory(ServiceAccountAccess access, String scopeKey) {
		syncStatePort.deleteByScopeKey(scopeKey);
		var result = initialSyncUseCase.synchronize(access, scopeKey);
		return new BackupResult(result.scopeKey(), result.fileCount(), true);
	}
}
