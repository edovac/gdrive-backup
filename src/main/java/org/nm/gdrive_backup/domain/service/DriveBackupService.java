package org.nm.gdrive_backup.domain.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.BackupMode;
import org.nm.gdrive_backup.domain.model.BackupResult;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StaleDrivePageTokenException;
import org.nm.gdrive_backup.domain.model.StoredDrive;
import org.nm.gdrive_backup.domain.port.in.DriveBackupUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveChangeSyncUseCase;
import org.nm.gdrive_backup.domain.port.in.InitialDriveSyncUseCase;
import org.nm.gdrive_backup.domain.port.out.DriveMetadataPort;
import org.nm.gdrive_backup.domain.port.out.SyncStatePort;

/** Selects the appropriate synchronization flow for a Drive scope. */
public class DriveBackupService implements DriveBackupUseCase {

	private final SyncStatePort syncStatePort;
	private final InitialDriveSyncUseCase initialSyncUseCase;
	private final DriveChangeSyncUseCase changeSyncUseCase;
	private final BackupActivity backupActivity;
	private final DriveMetadataPort driveMetadataPort;
	private final BackupProgressTracker progressTracker;
	private final BackupCancellation cancellation;

	public DriveBackupService(SyncStatePort syncStatePort, InitialDriveSyncUseCase initialSyncUseCase,
			DriveChangeSyncUseCase changeSyncUseCase, BackupActivity backupActivity) {
		this(syncStatePort, initialSyncUseCase, changeSyncUseCase, backupActivity, null, BackupProgressTracker.NO_OP,
				new BackupCancellation());
	}

	public DriveBackupService(SyncStatePort syncStatePort, InitialDriveSyncUseCase initialSyncUseCase,
			DriveChangeSyncUseCase changeSyncUseCase, BackupActivity backupActivity,
			DriveMetadataPort driveMetadataPort) {
		this(syncStatePort, initialSyncUseCase, changeSyncUseCase, backupActivity, driveMetadataPort,
				BackupProgressTracker.NO_OP, new BackupCancellation());
	}

	public DriveBackupService(SyncStatePort syncStatePort, InitialDriveSyncUseCase initialSyncUseCase,
			DriveChangeSyncUseCase changeSyncUseCase, BackupActivity backupActivity,
			DriveMetadataPort driveMetadataPort, BackupProgressTracker progressTracker) {
		this(syncStatePort, initialSyncUseCase, changeSyncUseCase, backupActivity, driveMetadataPort, progressTracker,
				new BackupCancellation());
	}

	public DriveBackupService(SyncStatePort syncStatePort, InitialDriveSyncUseCase initialSyncUseCase,
			DriveChangeSyncUseCase changeSyncUseCase, BackupActivity backupActivity,
			DriveMetadataPort driveMetadataPort, BackupProgressTracker progressTracker,
			BackupCancellation cancellation) {
		this.syncStatePort = syncStatePort;
		this.initialSyncUseCase = initialSyncUseCase;
		this.changeSyncUseCase = changeSyncUseCase;
		this.backupActivity = backupActivity;
		this.driveMetadataPort = driveMetadataPort;
		this.progressTracker = progressTracker;
		this.cancellation = cancellation;
	}

	@Override
	public BackupResult synchronize(ServiceAccountAccess access, DriveScope scope, BackupMode mode) {
		return backupActivity.duringBackup(() -> {
			cancellation.begin();
			return synchronizeScope(access, scope, mode);
		});
	}

	@Override
	public List<BackupResult> synchronizeSelectedDrives(ServiceAccountAccess access,
			List<AvailableDrive> selectedDrives, BackupMode mode) {
		if (selectedDrives == null || selectedDrives.isEmpty()) {
			throw new IllegalArgumentException("At least one drive must be selected");
		}
		return backupActivity.duringBackup(() -> {
			progressTracker.jobStarted(selectedDrives);
			cancellation.begin();
			List<BackupResult> results = new ArrayList<>();
			for (AvailableDrive drive : selectedDrives) {
				if (cancellation.isStopRequested()) {
					break;
				}
				DriveScope scope = drive.shared() ? DriveScope.sharedDrive(drive.id())
						: DriveScope.personal(access.impersonatedUserEmail());
				progressTracker.driveStarted(drive);
				results.add(synchronizeScope(access, scope, mode));
				progressTracker.driveCompleted();
				if (drive.shared() && driveMetadataPort != null) {
					driveMetadataPort.save(new StoredDrive(drive.id(), drive.name(), Instant.now()));
				}
			}
			progressTracker.jobFinished();
			return results;
		});
	}

	private BackupResult synchronizeScope(ServiceAccountAccess access, DriveScope scope, BackupMode mode) {
		if (mode == BackupMode.FULL) {
			return runFullInventory(access, scope);
		}
		if (syncStatePort.findByScopeKey(scope.key()).isEmpty()) {
			return runFullInventory(access, scope);
		}
		try {
			var result = changeSyncUseCase.synchronize(access, scope);
			return new BackupResult(result.scope(), result.changeCount(), false, cancellation.isImmediateStopRequested());
		} catch (StaleDrivePageTokenException exception) {
			return runFullInventory(access, scope);
		}
	}

	private BackupResult runFullInventory(ServiceAccountAccess access, DriveScope scope) {
		syncStatePort.deleteByScopeKey(scope.key());
		var result = initialSyncUseCase.synchronize(access, scope);
		return new BackupResult(result.scope(), result.fileCount(), true, cancellation.isImmediateStopRequested());
	}
}
