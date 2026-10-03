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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Selects the appropriate synchronization flow for a Drive scope. */
public class DriveBackupService implements DriveBackupUseCase {

	private static final Logger LOGGER = LoggerFactory.getLogger(DriveBackupService.class);

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
			LOGGER.info("Backup started: {} backup of {} drive(s) for {}", mode, selectedDrives.size(),
					access.impersonatedUserEmail());
			try {
				for (AvailableDrive drive : selectedDrives) {
					if (cancellation.isStopRequested()) {
						LOGGER.info("Backup stopped before {} because a stop was requested", drive.name());
						break;
					}
					DriveScope scope = drive.shared() ? DriveScope.sharedDrive(drive.id())
							: DriveScope.personal(access.impersonatedUserEmail());
					progressTracker.driveStarted(drive);
					LOGGER.info("Drive started: {} ({}, {})", drive.name(), scope.type(), scope.key());
					// One failing drive must not stop the others: each scope commits on its own, and a failed
					// one wrote nothing, so it simply replays from its last cursor on the next run.
					try {
						BackupResult result = synchronizeScope(access, scope, mode, drive.name());
						results.add(result);
						LOGGER.info("Drive finished: {} ({}), {} item(s) processed, {} file(s) skipped{}", drive.name(),
								result.initialSync() ? "full inventory" : "incremental", result.processedItemCount(),
								result.skippedFiles().size(), result.cancelled() ? ", cancelled before completion" : "");
					} catch (RuntimeException exception) {
						LOGGER.error("Drive failed: {} ({}, {}); nothing was committed for it and the next run replays "
								+ "from its last cursor", drive.name(), scope.type(), scope.key(), exception);
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
				LOGGER.info("Backup finished: {} of {} drive(s) failed", results.stream().filter(BackupResult::failed).count(),
						selectedDrives.size());
			}
			return results;
		});
	}

	/** The whole cause chain, so the UI shows why (a full disk, a 403) and not just the outermost wrapper's text. */
	private static String failureMessage(RuntimeException exception) {
		return DownloadOutcome.reasonOf(exception);
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
