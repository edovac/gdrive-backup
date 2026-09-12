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

	public InitialDriveSyncService(DriveFileListingPort fileListingPort, DriveChangePort changePort,
			FileMetadataPort fileMetadataPort, SyncStatePort syncStatePort) {
		this.fileListingPort = fileListingPort;
		this.changePort = changePort;
		this.fileMetadataPort = fileMetadataPort;
		this.syncStatePort = syncStatePort;
	}

	@Override
	public InitialSyncResult synchronize(ServiceAccountAccess access, String scopeKey) {
		if (syncStatePort.findByScopeKey(scopeKey).isPresent()) {
			throw new IllegalStateException("Drive scope already has a sync baseline: " + scopeKey);
		}
		var files = fileListingPort.listAllFiles(access, scopeKey);
		files.forEach(fileMetadataPort::save);
		String pageToken = changePort.getStartPageToken(access, scopeKey);
		syncStatePort.save(new SyncState(scopeKey, pageToken));
		return new InitialSyncResult(scopeKey, files.size(), pageToken);
	}
}