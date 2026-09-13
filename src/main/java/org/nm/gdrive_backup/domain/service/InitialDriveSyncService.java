package org.nm.gdrive_backup.domain.service;

import org.nm.gdrive_backup.domain.model.InitialSyncResult;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.SyncState;
import org.nm.gdrive_backup.domain.port.in.InitialDriveSyncUseCase;
import org.nm.gdrive_backup.domain.port.out.DriveChangePort;
import org.nm.gdrive_backup.domain.port.out.DriveFileListingPort;
import org.nm.gdrive_backup.domain.port.out.FileMetadataPort;
import org.nm.gdrive_backup.domain.port.out.SyncStatePort;

public class InitialDriveSyncService implements InitialDriveSyncUseCase {

	private final DriveFileListingPort fileListingPort;
	private final DriveChangePort changePort;
	private final FileMetadataPort fileMetadataPort;
	private final SyncStatePort syncStatePort;
	private final FileContentBackupService contentBackupService;

	public InitialDriveSyncService(DriveFileListingPort fileListingPort, DriveChangePort changePort,
			FileMetadataPort fileMetadataPort, SyncStatePort syncStatePort) {
		this(fileListingPort, changePort, fileMetadataPort, syncStatePort, null);
	}

	public InitialDriveSyncService(DriveFileListingPort fileListingPort, DriveChangePort changePort,
			FileMetadataPort fileMetadataPort, SyncStatePort syncStatePort,
			FileContentBackupService contentBackupService) {
		this.fileListingPort = fileListingPort;
		this.changePort = changePort;
		this.fileMetadataPort = fileMetadataPort;
		this.syncStatePort = syncStatePort;
		this.contentBackupService = contentBackupService;
	}

	@Override
	public InitialSyncResult synchronize(ServiceAccountAccess access, String scopeKey) {
		if (syncStatePort.findByScopeKey(scopeKey).isPresent()) {
			throw new IllegalStateException("Drive scope already has a sync baseline: " + scopeKey);
		}
		var files = fileListingPort.listAllFiles(access, scopeKey);
		files.forEach(file -> {
			fileMetadataPort.save(file);
			if (contentBackupService != null && !isFolder(file)) {
				var version = contentBackupService.backup(access, file);
				fileMetadataPort.save(withCurrentVersion(file, version.id()));
			}
		});
		String pageToken = changePort.getStartPageToken(access, scopeKey);
		syncStatePort.save(new SyncState(scopeKey, pageToken));
		return new InitialSyncResult(scopeKey, files.size(), pageToken);
	}

	private static boolean isFolder(org.nm.gdrive_backup.domain.model.StoredFile file) {
		return "application/vnd.google-apps.folder".equals(file.mimeType());
	}

	private static org.nm.gdrive_backup.domain.model.StoredFile withCurrentVersion(
			org.nm.gdrive_backup.domain.model.StoredFile file, Long versionId) {
		return new org.nm.gdrive_backup.domain.model.StoredFile(file.fileId(), file.ownerScope(), file.name(),
				file.parents(), file.driveId(), file.mimeType(), file.trashed(), file.headRevisionId(), versionId);
	}
}
