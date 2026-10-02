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
			return synchronizeScope(access, scope, mode, null);
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
			try {
				for (AvailableDrive drive : selectedDrives) {
					if (cancellation.isStopRequested()) {
						break;
					}
					DriveScope scope = drive.shared() ? DriveScope.sharedDrive(drive.id())
							: DriveScope.personal(access.impersonatedUserEmail());
					progressTracker.driveStarted(drive);
					// One failing drive must not stop the others: each scope commits on its own, and a failed
					// one wrote nothing, so it simply replays from its last cursor on the next run.
					try {
						results.add(synchronizeScope(access, scope, mode, drive.name()));
					} catch (RuntimeException exception) {
						results.add(BackupResult.failed(scope, failureMessage(exception)));
						progressTracker.driveFailed();
						continue;
					}
					progressTracker.driveCompleted();
					if (drive.shared() && driveMetadataPort != null) {
						driveMetadataPort.save(new StoredDrive(drive.id(), drive.name(), Instant.now()));
					}
				}
			} finally {
				progressTracker.jobFinished();
			}
			return results;
		});
	}

	private static String failureMessage(RuntimeException exception) {
		String message = exception.getMessage();
		return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
	}

	private BackupResult synchronizeScope(ServiceAccountAccess access, DriveScope scope, BackupMode mode,
			String scopeDisplayName) {
		if (mode == BackupMode.FULL || syncStatePort.findByScopeKey(scope.key()).isEmpty()) {
			return runFullInventory(access, scope, scopeDisplayName);
		}
		try {
			var result = changeSyncUseCase.synchronize(access, scope, scopeDisplayName);
			return new BackupResult(result.scope(), result.changeCount(), false, result.cancelled(), null, result.failures());
		} catch (StaleDrivePageTokenException exception) {
			return runFullInventory(access, scope, scopeDisplayName);
		}
	}

	// The existing sync_state row is deliberately kept: the full run replaces it in its own commit, so a
	// failed or cancelled full run leaves the previous cursor (and the incremental chain) intact.
	private BackupResult runFullInventory(ServiceAccountAccess access, DriveScope scope, String scopeDisplayName) {
		var result = initialSyncUseCase.synchronize(access, scope, scopeDisplayName);
		return new BackupResult(result.scope(), result.fileCount(), true, result.cancelled(), null, result.failures());
	}
}
