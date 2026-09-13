package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.nm.gdrive_backup.domain.model.DriveChange;
import org.nm.gdrive_backup.domain.model.DriveChangePage;
import org.nm.gdrive_backup.domain.model.FileVersion;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.model.SyncResult;
import org.nm.gdrive_backup.domain.model.SyncState;
import org.nm.gdrive_backup.domain.port.out.DriveChangePort;
import org.nm.gdrive_backup.domain.port.out.FileEventPort;
import org.nm.gdrive_backup.domain.port.out.FileMetadataPort;
import org.nm.gdrive_backup.domain.port.out.SyncStatePort;

class DriveChangeSyncServiceTest {

	private static final ServiceAccountAccess ACCESS = new ServiceAccountAccess(
			UUID.randomUUID(), "user@example.com", Instant.now().plusSeconds(3600), Set.of("drive.readonly"));

	@Test
	void drainsPagesAndPersistsNewStartToken() {
		DriveChangePort changePort = mock(DriveChangePort.class);
		SyncStatePort statePort = mock(SyncStatePort.class);
		FileMetadataPort metadataPort = mock(FileMetadataPort.class);
		FileEventPort eventPort = mock(FileEventPort.class);
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.of(
				new SyncState("user@example.com", "old-token")));
		when(changePort.listChanges(ACCESS, "user@example.com", "old-token"))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-1", false, null)), "next-token", null));
		when(changePort.listChanges(ACCESS, "user@example.com", "next-token"))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-2", true, null)), null, "new-token"));
		DriveChangeSyncService service = new DriveChangeSyncService(
				changePort, statePort, metadataPort, eventPort);

		SyncResult result = service.synchronize(ACCESS, "user@example.com");

		assertEquals(new SyncResult("user@example.com", 2, "new-token"), result);
		verify(statePort).save(new SyncState("user@example.com", "new-token"));
	}

	@Test
	void establishesStartTokenForAnUninitializedScope() {
		DriveChangePort changePort = mock(DriveChangePort.class);
		SyncStatePort statePort = mock(SyncStatePort.class);
		FileMetadataPort metadataPort = mock(FileMetadataPort.class);
		FileEventPort eventPort = mock(FileEventPort.class);
		when(statePort.findByScopeKey("drive-1")).thenReturn(Optional.empty());
		when(changePort.getStartPageToken(ACCESS, "drive-1")).thenReturn("start-token");
		when(changePort.listChanges(ACCESS, "drive-1", "start-token"))
				.thenReturn(new DriveChangePage(List.of(), null, "new-token"));

		SyncResult result = new DriveChangeSyncService(
				changePort, statePort, metadataPort, eventPort).synchronize(ACCESS, "drive-1");

		assertEquals(new SyncResult("drive-1", 0, "new-token"), result);
		verify(statePort).save(new SyncState("drive-1", "new-token"));
	}

	@Test
	void recordsMetadataDifferencesAsFileEvents() {
		DriveChangePort changePort = mock(DriveChangePort.class);
		SyncStatePort statePort = mock(SyncStatePort.class);
		FileMetadataPort metadataPort = mock(FileMetadataPort.class);
		FileEventPort eventPort = mock(FileEventPort.class);
		StoredFile previous = new StoredFile(
				"file-1", "user@example.com", "Report", "root", null,
				"application/pdf", false, "revision-1", null);
		StoredFile current = new StoredFile(
				"file-1", "user@example.com", "Renamed report", "folder-1", "drive-1",
				"application/pdf", true, "revision-2", null);
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.of(
				new SyncState("user@example.com", "old-token")));
		when(changePort.listChanges(ACCESS, "user@example.com", "old-token"))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-1", false, current)), null, "new-token"));
		when(metadataPort.findByFileId("file-1")).thenReturn(Optional.of(previous));

		new DriveChangeSyncService(changePort, statePort, metadataPort, eventPort)
				.synchronize(ACCESS, "user@example.com");

		verify(eventPort, times(4)).save(org.mockito.ArgumentMatchers.any());
		verify(metadataPort).save(current);
	}

	@Test
	void backsUpChangedContentAndLinksTheNewVersion() {
		DriveChangePort changePort = mock(DriveChangePort.class);
		SyncStatePort statePort = mock(SyncStatePort.class);
		FileMetadataPort metadataPort = mock(FileMetadataPort.class);
		FileEventPort eventPort = mock(FileEventPort.class);
		FileContentBackupService contentBackup = mock(FileContentBackupService.class);
		StoredFile previous = new StoredFile("file-1", "user@example.com", "Report", "root", null,
				"text/plain", false, "revision-1", 3L);
		StoredFile current = new StoredFile("file-1", "user@example.com", "Report", "root", null,
				"text/plain", false, "revision-2", null);
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.of(
				new SyncState("user@example.com", "old-token")));
		when(changePort.listChanges(ACCESS, "user@example.com", "old-token"))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-1", false, current)), null, "new-token"));
		when(metadataPort.findByFileId("file-1")).thenReturn(Optional.of(previous));
		when(contentBackup.backup(ACCESS, current)).thenReturn(new FileVersion(8L, "file-1", "revision-2",
				Instant.now(), "backup/report", 22));

		new DriveChangeSyncService(changePort, statePort, metadataPort, eventPort, contentBackup)
				.synchronize(ACCESS, "user@example.com");

		verify(contentBackup).backup(ACCESS, current);
		verify(metadataPort).save(new StoredFile("file-1", "user@example.com", "Report", "root", null,
				"text/plain", false, "revision-2", 8L));
	}

	@Test
	void reportsEachProcessedChangeWithoutEverKnowingATotal() {
		DriveChangePort changePort = mock(DriveChangePort.class);
		SyncStatePort statePort = mock(SyncStatePort.class);
		FileMetadataPort metadataPort = mock(FileMetadataPort.class);
		FileEventPort eventPort = mock(FileEventPort.class);
		BackupProgressTracker progressTracker = mock(BackupProgressTracker.class);
		StoredFile file = new StoredFile("file-2", "user@example.com", "Report", "root", null,
				"text/plain", false, "revision-1", null);
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.of(
				new SyncState("user@example.com", "old-token")));
		when(changePort.listChanges(ACCESS, "user@example.com", "old-token"))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-1", true, null),
						new DriveChange("file-2", false, file)), null, "new-token"));

		new DriveChangeSyncService(changePort, statePort, metadataPort, eventPort, null, progressTracker)
				.synchronize(ACCESS, "user@example.com");

		InOrder order = inOrder(progressTracker);
		order.verify(progressTracker).itemProcessed("file-1");
		order.verify(progressTracker).itemProcessed("Report");
		verify(progressTracker, org.mockito.Mockito.never()).enumerated(org.mockito.ArgumentMatchers.anyInt());
	}
}
