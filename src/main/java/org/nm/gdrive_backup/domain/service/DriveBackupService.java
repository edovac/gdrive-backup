package org.nm.gdrive_backup.domain.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.BackupMode;
import org.nm.gdrive_backup.domain.model.BackupResult;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StaleDrivePageTokenException;
import org.nm.gdrive_backup.domain.model.StoredDrive;
import org.nm.gdrive_backup.domain.port.in.DriveBackupUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveChangeSyncUseCase;
import org.nm.gdrive_backup.domain.port.in.InitialDriveSyncUseCase;
import org.nm.gdrive_backup.domain.port.out.DriveMetadataPort;
import org.nm.gdrive_backup.domain.port.out.DriveReadPort;
import org.nm.gdrive_backup.domain.port.out.SyncStatePort;

/** Selects the appropriate synchronization flow for a Drive scope. */
public class DriveBackupService implements DriveBackupUseCase {

	private final SyncStatePort syncStatePort;
	private final InitialDriveSyncUseCase initialSyncUseCase;
	private final DriveChangeSyncUseCase changeSyncUseCase;
	private final BackupActivity backupActivity;
	private final DriveReadPort driveReadPort;
	private final DriveMetadataPort driveMetadataPort;

	public DriveBackupService(SyncStatePort syncStatePort, InitialDriveSyncUseCase initialSyncUseCase,
			DriveChangeSyncUseCase changeSyncUseCase, BackupActivity backupActivity) {
		this(syncStatePort, initialSyncUseCase, changeSyncUseCase, backupActivity, null, null);
	}

	public DriveBackupService(SyncStatePort syncStatePort, InitialDriveSyncUseCase initialSyncUseCase,
			DriveChangeSyncUseCase changeSyncUseCase, BackupActivity backupActivity,
			DriveReadPort driveReadPort, DriveMetadataPort driveMetadataPort) {
		this.syncStatePort = syncStatePort;
		this.initialSyncUseCase = initialSyncUseCase;
		this.changeSyncUseCase = changeSyncUseCase;
		this.backupActivity = backupActivity;
		this.driveReadPort = driveReadPort;
		this.driveMetadataPort = driveMetadataPort;
	}

	@Override
	public BackupResult synchronize(ServiceAccountAccess access, String scopeKey, BackupMode mode) {
		return backupActivity.duringBackup(() -> synchronizeScope(access, scopeKey, mode));
	}

	@Override
	public List<BackupResult> synchronizeVisibleScopes(ServiceAccountAccess access, BackupMode mode) {
		return backupActivity.duringBackup(() -> {
			List<BackupResult> results = new ArrayList<>();
			results.add(synchronizeScope(access, access.impersonatedUserEmail(), mode));
			if (driveReadPort != null) {
				for (AvailableDrive drive : driveReadPort.listAvailableDrives(access)) {
					if (drive.shared()) {
						results.add(synchronizeScope(access, drive.id(), mode));
						driveMetadataPort.save(new StoredDrive(drive.id(), drive.name(), Instant.now()));
					}
				}
			}
			return results;
		});
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
