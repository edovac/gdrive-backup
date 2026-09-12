package org.nm.gdrive_backup.domain.service;

import org.nm.gdrive_backup.domain.model.DriveChangePage;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.SyncResult;
import org.nm.gdrive_backup.domain.model.SyncState;
import org.nm.gdrive_backup.domain.port.in.DriveChangeSyncUseCase;
import org.nm.gdrive_backup.domain.port.out.DriveChangePort;
import org.nm.gdrive_backup.domain.port.out.SyncStatePort;

public class DriveChangeSyncService implements DriveChangeSyncUseCase {

	private final DriveChangePort changePort;
	private final SyncStatePort syncStatePort;

	public DriveChangeSyncService(DriveChangePort changePort, SyncStatePort syncStatePort) {
		this.changePort = changePort;
		this.syncStatePort = syncStatePort;
	}

	@Override
	public SyncResult synchronize(ServiceAccountAccess access, String scopeKey) {
		String pageToken = syncStatePort.findByScopeKey(scopeKey)
				.map(SyncState::pageToken)
				.orElseGet(() -> changePort.getStartPageToken(access, scopeKey));
		int changeCount = 0;
		String newStartPageToken = null;
		while (newStartPageToken == null) {
			DriveChangePage page = changePort.listChanges(access, scopeKey, pageToken);
			changeCount += page.changes().size();
			newStartPageToken = page.newStartPageToken();
			if (newStartPageToken == null) {
				if (page.nextPageToken() == null || page.nextPageToken().isBlank()) {
					throw new IllegalStateException("Drive change page has neither next nor new start token");
				}
				pageToken = page.nextPageToken();
			}
		}
		syncStatePort.save(new SyncState(scopeKey, newStartPageToken));
		return new SyncResult(scopeKey, changeCount, newStartPageToken);
	}
}