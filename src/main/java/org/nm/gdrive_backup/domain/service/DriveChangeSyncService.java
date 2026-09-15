package org.nm.gdrive_backup.domain.service;

import org.nm.gdrive_backup.domain.model.DriveChangePage;
import org.nm.gdrive_backup.domain.model.DriveChange;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.FileEvent;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.model.SyncResult;
import org.nm.gdrive_backup.domain.model.SyncState;
import org.nm.gdrive_backup.domain.port.in.DriveChangeSyncUseCase;
import org.nm.gdrive_backup.domain.port.out.DriveChangePort;
import org.nm.gdrive_backup.domain.port.out.FileEventPort;
import org.nm.gdrive_backup.domain.port.out.FileMetadataPort;
import org.nm.gdrive_backup.domain.port.out.SyncStatePort;

import java.time.Instant;
import java.util.Optional;

public class DriveChangeSyncService implements DriveChangeSyncUseCase {

	private final DriveChangePort changePort;
	private final SyncStatePort syncStatePort;
	private final FileMetadataPort fileMetadataPort;
	private final FileEventPort fileEventPort;
	private final FileContentBackupService contentBackupService;
	private final BackupProgressTracker progressTracker;
	private final BackupCancellation cancellation;

	public DriveChangeSyncService(DriveChangePort changePort, SyncStatePort syncStatePort,
			FileMetadataPort fileMetadataPort, FileEventPort fileEventPort) {
		this(changePort, syncStatePort, fileMetadataPort, fileEventPort, (FileContentBackupService) null,
				BackupProgressTracker.NO_OP, new BackupCancellation());
	}

	public DriveChangeSyncService(DriveChangePort changePort, SyncStatePort syncStatePort,
			FileMetadataPort fileMetadataPort, FileEventPort fileEventPort, FileContentBackupService contentBackupService) {
		this(changePort, syncStatePort, fileMetadataPort, fileEventPort, contentBackupService,
				BackupProgressTracker.NO_OP, new BackupCancellation());
	}

	public DriveChangeSyncService(DriveChangePort changePort, SyncStatePort syncStatePort,
			FileMetadataPort fileMetadataPort, FileEventPort fileEventPort, FileContentBackupService contentBackupService,
			BackupProgressTracker progressTracker) {
		this(changePort, syncStatePort, fileMetadataPort, fileEventPort, contentBackupService, progressTracker,
				new BackupCancellation());
	}

	public DriveChangeSyncService(DriveChangePort changePort, SyncStatePort syncStatePort,
			FileMetadataPort fileMetadataPort, FileEventPort fileEventPort, FileContentBackupService contentBackupService,
			BackupProgressTracker progressTracker, BackupCancellation cancellation) {
		this.changePort = changePort;
		this.syncStatePort = syncStatePort;
		this.fileMetadataPort = fileMetadataPort;
		this.fileEventPort = fileEventPort;
		this.contentBackupService = contentBackupService;
		this.progressTracker = progressTracker;
		this.cancellation = cancellation;
	}

	@Override
	public SyncResult synchronize(ServiceAccountAccess access, DriveScope scope) {
		String pageToken = syncStatePort.findByScopeKey(scope.key())
				.map(SyncState::pageToken)
				.orElseGet(() -> changePort.getStartPageToken(access, scope));
		int changeCount = 0;
		String newStartPageToken = null;
		while (newStartPageToken == null) {
			if (cancellation.isImmediateStopRequested()) {
				break;
			}
			DriveChangePage page = changePort.listChanges(access, scope, pageToken);
			changeCount += page.changes().size();
			page.changes().forEach(change -> {
				applyChange(access, change);
				progressTracker.itemProcessed(itemLabel(change));
			});
			newStartPageToken = page.newStartPageToken();
			if (newStartPageToken == null && (page.nextPageToken() == null || page.nextPageToken().isBlank())) {
				throw new IllegalStateException("Drive change page has neither next nor new start token");
			}
			// Checkpoint after every page, not just the last one: on a crash or a
			// cancelled-and-resumed run, this is what stops the next run from replaying
			// already-applied pages and re-inserting duplicate file_events.
			pageToken = newStartPageToken != null ? newStartPageToken : page.nextPageToken();
			syncStatePort.save(new SyncState(scope.key(), pageToken));
		}
		return new SyncResult(scope, changeCount, pageToken);
	}

	private static String itemLabel(DriveChange change) {
		return change.file() != null ? change.file().name() : change.fileId();
	}

	private void applyChange(ServiceAccountAccess access, DriveChange change) {
		if (change.removed()) {
			fileEventPort.save(new FileEvent(null, change.fileId(), "delete", null, null, Instant.now(), null));
			return;
		}
		StoredFile current = change.file();
		if (current == null) {
			return;
		}
		Optional<StoredFile> previous = fileMetadataPort.findByFileId(current.fileId());
		previous.ifPresent(old -> recordDifferences(old, current));
		fileMetadataPort.save(current);
		if (shouldBackUpContent(previous, current)) {
			var version = contentBackupService.backup(access, current);
			fileMetadataPort.save(withCurrentVersion(current, version.id()));
		}
	}

	private boolean shouldBackUpContent(Optional<StoredFile> previous, StoredFile current) {
		return contentBackupService != null && !isFolder(current) && current.headRevisionId() != null
				&& !current.headRevisionId().isBlank()
				&& previous.map(file -> !java.util.Objects.equals(file.headRevisionId(), current.headRevisionId())
						|| file.currentVersionId() == null).orElse(true);
	}

	private static boolean isFolder(StoredFile file) {
		return "application/vnd.google-apps.folder".equals(file.mimeType());
	}

	private static StoredFile withCurrentVersion(StoredFile file, Long versionId) {
		return new StoredFile(file.fileId(), file.ownerScope(), file.name(), file.parents(), file.driveId(),
				file.mimeType(), file.trashed(), file.headRevisionId(), versionId);
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
		fileEventPort.save(new FileEvent(null, fileId, eventType, oldValue, newValue, Instant.now(), null));
	}
}
