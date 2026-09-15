package org.nm.gdrive_backup.domain.service;

import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.InitialSyncResult;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.SyncState;
import org.nm.gdrive_backup.domain.port.in.InitialDriveSyncUseCase;
import org.nm.gdrive_backup.domain.port.out.DriveChangePort;
import org.nm.gdrive_backup.domain.port.out.DriveFileListingPort;
import org.nm.gdrive_backup.domain.port.out.FileMetadataPort;
import org.nm.gdrive_backup.domain.port.out.SyncStatePort;

import java.util.Optional;

public class InitialDriveSyncService implements InitialDriveSyncUseCase {

	private final DriveFileListingPort fileListingPort;
	private final DriveChangePort changePort;
	private final FileMetadataPort fileMetadataPort;
	private final SyncStatePort syncStatePort;
	private final FileContentBackupService contentBackupService;
	private final BackupProgressTracker progressTracker;
	private final BackupCancellation cancellation;

	public InitialDriveSyncService(DriveFileListingPort fileListingPort, DriveChangePort changePort,
			FileMetadataPort fileMetadataPort, SyncStatePort syncStatePort) {
		this(fileListingPort, changePort, fileMetadataPort, syncStatePort, null, BackupProgressTracker.NO_OP,
				new BackupCancellation());
	}

	public InitialDriveSyncService(DriveFileListingPort fileListingPort, DriveChangePort changePort,
			FileMetadataPort fileMetadataPort, SyncStatePort syncStatePort,
			FileContentBackupService contentBackupService) {
		this(fileListingPort, changePort, fileMetadataPort, syncStatePort, contentBackupService,
				BackupProgressTracker.NO_OP, new BackupCancellation());
	}

	public InitialDriveSyncService(DriveFileListingPort fileListingPort, DriveChangePort changePort,
			FileMetadataPort fileMetadataPort, SyncStatePort syncStatePort,
			FileContentBackupService contentBackupService, BackupProgressTracker progressTracker) {
		this(fileListingPort, changePort, fileMetadataPort, syncStatePort, contentBackupService, progressTracker,
				new BackupCancellation());
	}

	public InitialDriveSyncService(DriveFileListingPort fileListingPort, DriveChangePort changePort,
			FileMetadataPort fileMetadataPort, SyncStatePort syncStatePort,
			FileContentBackupService contentBackupService, BackupProgressTracker progressTracker,
			BackupCancellation cancellation) {
		this.fileListingPort = fileListingPort;
		this.changePort = changePort;
		this.fileMetadataPort = fileMetadataPort;
		this.syncStatePort = syncStatePort;
		this.contentBackupService = contentBackupService;
		this.progressTracker = progressTracker;
		this.cancellation = cancellation;
	}

	@Override
	public InitialSyncResult synchronize(ServiceAccountAccess access, DriveScope scope) {
		if (syncStatePort.findByScopeKey(scope.key()).isPresent()) {
			throw new IllegalStateException("Drive scope already has a sync baseline: " + scope.key());
		}
		progressTracker.enumerating();
		var files = fileListingPort.listAllFiles(access, scope);
		progressTracker.enumerated(files.size());
		int processedCount = 0;
		for (org.nm.gdrive_backup.domain.model.StoredFile file : files) {
			if (cancellation.isImmediateStopRequested()) {
				break;
			}
			Optional<org.nm.gdrive_backup.domain.model.StoredFile> previous = fileMetadataPort.findByFileId(file.fileId());
			org.nm.gdrive_backup.domain.model.StoredFile metadata = previous
					.filter(existing -> java.util.Objects.equals(existing.headRevisionId(), file.headRevisionId()))
					.map(existing -> withCurrentVersion(file, existing.currentVersionId()))
					.orElse(file);
			fileMetadataPort.save(metadata);
			if (shouldBackUpContent(previous, file)) {
				var version = contentBackupService.backup(access, file);
				fileMetadataPort.save(withCurrentVersion(file, version.id()));
			}
			progressTracker.itemProcessed(file.name());
			processedCount++;
		}
		if (processedCount < files.size()) {
			// A stopped-early run must never establish a baseline: doing so would make a
			// later incremental sync silently skip every file this run never reached.
			return new InitialSyncResult(scope, processedCount, null);
		}
		String pageToken = changePort.getStartPageToken(access, scope);
		syncStatePort.save(new SyncState(scope.key(), pageToken));
		return new InitialSyncResult(scope, files.size(), pageToken);
	}

	private static boolean isFolder(org.nm.gdrive_backup.domain.model.StoredFile file) {
		return "application/vnd.google-apps.folder".equals(file.mimeType());
	}

	private boolean shouldBackUpContent(Optional<org.nm.gdrive_backup.domain.model.StoredFile> previous,
			org.nm.gdrive_backup.domain.model.StoredFile file) {
		return contentBackupService != null && !isFolder(file) && file.headRevisionId() != null
				&& !file.headRevisionId().isBlank()
				&& previous.map(existing -> !java.util.Objects.equals(existing.headRevisionId(), file.headRevisionId())
						|| existing.currentVersionId() == null).orElse(true);
	}

	private static org.nm.gdrive_backup.domain.model.StoredFile withCurrentVersion(
			org.nm.gdrive_backup.domain.model.StoredFile file, Long versionId) {
		return new org.nm.gdrive_backup.domain.model.StoredFile(file.fileId(), file.ownerScope(), file.name(),
				file.parents(), file.driveId(), file.mimeType(), file.trashed(), file.headRevisionId(), versionId);
	}
}
