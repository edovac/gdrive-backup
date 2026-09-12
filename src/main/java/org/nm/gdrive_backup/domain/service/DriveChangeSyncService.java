package org.nm.gdrive_backup.domain.service;

import org.nm.gdrive_backup.domain.model.DriveChangePage;
import org.nm.gdrive_backup.domain.model.DriveChange;
import org.nm.gdrive_backup.domain.model.FileEvent;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.model.SyncResult;
import org.nm.gdrive_backup.domain.model.SyncState;
import org.nm.gdrive_backup.domain.port.in.DriveChangeSyncUseCase;
import org.nm.gdrive_backup.domain.port.out.DriveChangePort;
import org.nm.gdrive_backup.domain.port.out.FileEventPort;
import org.nm.gdrive_backup.domain.port.out.FileMetadataPort;
import org.nm.gdrive_backup.domain.port.out.FileVersionPort;
import org.nm.gdrive_backup.domain.port.out.SyncStatePort;

import java.time.Instant;
import java.util.Optional;

public class DriveChangeSyncService implements DriveChangeSyncUseCase {

	private final DriveChangePort changePort;
	private final SyncStatePort syncStatePort;
	private final FileMetadataPort fileMetadataPort;
	private final FileEventPort fileEventPort;
	private final FileVersionPort fileVersionPort;

	public DriveChangeSyncService(DriveChangePort changePort, SyncStatePort syncStatePort,
			FileMetadataPort fileMetadataPort, FileEventPort fileEventPort, FileVersionPort fileVersionPort) {
		this.changePort = changePort;
		this.syncStatePort = syncStatePort;
		this.fileMetadataPort = fileMetadataPort;
		this.fileEventPort = fileEventPort;
		this.fileVersionPort = fileVersionPort;
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
			page.changes().forEach(this::applyChange);
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

	private void applyChange(DriveChange change) {
		if (change.removed()) {
			fileEventPort.save(new FileEvent(null, change.fileId(), "delete", null, null, Instant.now()));
			return;
		}
		StoredFile current = change.file();
		if (current == null) {
			return;
		}
		Optional<StoredFile> previous = fileMetadataPort.findByFileId(current.fileId());
		previous.ifPresent(old -> recordDifferences(old, current));
		fileMetadataPort.save(current);
	}

	private void recordDifferences(StoredFile previous, StoredFile current) {
		if (!previous.name().equals(current.name())) {
			recordEvent(current.fileId(), "rename", previous.name(), current.name());
		}
		if (!previous.parents().equals(current.parents()) || !java.util.Objects.equals(previous.driveId(), current.driveId())) {
			recordEvent(current.fileId(), "move", previous.parents(), current.parents());
		}
		if (previous.trashed() != current.trashed()) {
			recordEvent(current.fileId(), current.trashed() ? "trash" : "untrash",
					Boolean.toString(previous.trashed()), Boolean.toString(current.trashed()));
		}
		if (!java.util.Objects.equals(previous.headRevisionId(), current.headRevisionId())) {
			recordEvent(current.fileId(), "content", previous.headRevisionId(), current.headRevisionId());
		}
	}

	private void recordEvent(String fileId, String eventType, String oldValue, String newValue) {
		fileEventPort.save(new FileEvent(null, fileId, eventType, oldValue, newValue, Instant.now()));
	}
}