package org.nm.gdrive_backup.domain.service;

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

	public InitialDriveSyncService(DriveFileListingPort fileListingPort, DriveChangePort changePort,
			FileMetadataPort fileMetadataPort, SyncStatePort syncStatePort) {
		this(fileListingPort, changePort, fileMetadataPort, syncStatePort, null, BackupProgressTracker.NO_OP);
	}

	public InitialDriveSyncService(DriveFileListingPort fileListingPort, DriveChangePort changePort,
			FileMetadataPort fileMetadataPort, SyncStatePort syncStatePort,
			FileContentBackupService contentBackupService) {
		this(fileListingPort, changePort, fileMetadataPort, syncStatePort, contentBackupService,
				BackupProgressTracker.NO_OP);
	}

	public InitialDriveSyncService(DriveFileListingPort fileListingPort, DriveChangePort changePort,
			FileMetadataPort fileMetadataPort, SyncStatePort syncStatePort,
			FileContentBackupService contentBackupService, BackupProgressTracker progressTracker) {
		this.fileListingPort = fileListingPort;
		this.changePort = changePort;
		this.fileMetadataPort = fileMetadataPort;
		this.syncStatePort = syncStatePort;
		this.contentBackupService = contentBackupService;
		this.progressTracker = progressTracker;
	}

	@Override
	public InitialSyncResult synchronize(ServiceAccountAccess access, String scopeKey) {
		if (syncStatePort.findByScopeKey(scopeKey).isPresent()) {
			throw new IllegalStateException("Drive scope already has a sync baseline: " + scopeKey);
		}
		progressTracker.enumerating();
		var files = fileListingPort.listAllFiles(access, scopeKey);
		progressTracker.enumerated(files.size());
		files.forEach(file -> {
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
		});
		String pageToken = changePort.getStartPageToken(access, scopeKey);
		syncStatePort.save(new SyncState(scopeKey, pageToken));
		return new InitialSyncResult(scopeKey, files.size(), pageToken);
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
