package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.nm.gdrive_backup.domain.model.BackupStopMode;
import org.nm.gdrive_backup.domain.model.InitialSyncResult;
import org.nm.gdrive_backup.domain.model.FileCapture;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.model.SyncState;
import org.nm.gdrive_backup.domain.port.out.DriveChangePort;
import org.nm.gdrive_backup.domain.port.out.DriveFileListingPort;
import org.nm.gdrive_backup.domain.port.out.FileMetadataPort;
import org.nm.gdrive_backup.domain.port.out.SyncStatePort;

class InitialDriveSyncServiceTest {

	private static final ServiceAccountAccess ACCESS = new ServiceAccountAccess(
			UUID.randomUUID(), "user@example.com", Instant.now().plusSeconds(3600), Set.of("drive.readonly"));

	@Test
	void persistsAllListedFilesBeforeSavingStartToken() {
		DriveFileListingPort listingPort = mock(DriveFileListingPort.class);
		DriveChangePort changePort = mock(DriveChangePort.class);
		FileMetadataPort metadataPort = mock(FileMetadataPort.class);
		SyncStatePort statePort = mock(SyncStatePort.class);
		StoredFile first = new StoredFile("file-1", "user@example.com", "A", "root", null,
				"text/plain", false, "revision-1", null);
		StoredFile second = new StoredFile("file-2", "user@example.com", "B", "root", null,
				"text/plain", false, "revision-2", null);
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.empty());
		when(listingPort.listAllFiles(ACCESS, "user@example.com")).thenReturn(List.of(first, second));
		when(changePort.getStartPageToken(ACCESS, "user@example.com")).thenReturn("start-token");

		InitialSyncResult result = new InitialDriveSyncService(listingPort, changePort, metadataPort, statePort)
				.synchronize(ACCESS, "user@example.com");

		assertEquals(new InitialSyncResult("user@example.com", 2, "start-token"), result);
		InOrder order = inOrder(metadataPort, changePort, statePort);
		order.verify(metadataPort).save(first);
		order.verify(metadataPort).save(second);
		order.verify(changePort).getStartPageToken(ACCESS, "user@example.com");
		order.verify(statePort).save(new SyncState("user@example.com", "start-token"));
	}

	@Test
	void refusesToReplaceAnExistingBaseline() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		when(statePort.findByScopeKey("user@example.com")).thenReturn(
				Optional.of(new SyncState("user@example.com", "existing-token")));

		InitialDriveSyncService service = new InitialDriveSyncService(
				mock(DriveFileListingPort.class), mock(DriveChangePort.class), mock(FileMetadataPort.class), statePort);

		assertThrows(IllegalStateException.class, () -> service.synchronize(ACCESS, "user@example.com"));
		verify(statePort).findByScopeKey("user@example.com");
	}

	@Test
	void backsUpFilesButSkipsFoldersBeforeSavingBaseline() {
		DriveFileListingPort listingPort = mock(DriveFileListingPort.class);
		DriveChangePort changePort = mock(DriveChangePort.class);
		FileMetadataPort metadataPort = mock(FileMetadataPort.class);
		SyncStatePort statePort = mock(SyncStatePort.class);
		FileContentBackupService contentService = mock(FileContentBackupService.class);
		StoredFile file = new StoredFile("file-1", "user@example.com", "Report", "root", null,
				"text/plain", false, "revision-1", null);
		StoredFile folder = new StoredFile("folder-1", "user@example.com", "Folder", "root", null,
				"application/vnd.google-apps.folder", false, null, null);
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.empty());
		when(listingPort.listAllFiles(ACCESS, "user@example.com")).thenReturn(List.of(file, folder));
		when(changePort.getStartPageToken(ACCESS, "user@example.com")).thenReturn("start-token");
		when(contentService.backup(ACCESS, file)).thenReturn(new FileCapture(7L, "file-1", "revision-1",
				Instant.now(), "backup/report", 12, null));

		new InitialDriveSyncService(listingPort, changePort, metadataPort, statePort, contentService)
				.synchronize(ACCESS, "user@example.com");

		verify(contentService).backup(ACCESS, file);
		verify(metadataPort).save(new StoredFile("file-1", "user@example.com", "Report", "root", null,
				"text/plain", false, "revision-1", 7L));
		verify(contentService, never()).backup(ACCESS, folder);
		verify(statePort).save(new SyncState("user@example.com", "start-token"));
	}

	@Test
	void retainsExistingVersionWhenARecoveryInventoryFindsAnUnchangedFile() {
		DriveFileListingPort listingPort = mock(DriveFileListingPort.class);
		DriveChangePort changePort = mock(DriveChangePort.class);
		FileMetadataPort metadataPort = mock(FileMetadataPort.class);
		SyncStatePort statePort = mock(SyncStatePort.class);
		FileContentBackupService contentService = mock(FileContentBackupService.class);
		StoredFile current = new StoredFile("file-1", "user@example.com", "Report", "root", null,
				"text/plain", false, "revision-1", null);
		StoredFile existing = new StoredFile("file-1", "user@example.com", "Old report", "root", null,
				"text/plain", false, "revision-1", 7L);
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.empty());
		when(listingPort.listAllFiles(ACCESS, "user@example.com")).thenReturn(List.of(current));
		when(metadataPort.findByFileId("file-1")).thenReturn(Optional.of(existing));
		when(changePort.getStartPageToken(ACCESS, "user@example.com")).thenReturn("fresh-token");

		new InitialDriveSyncService(listingPort, changePort, metadataPort, statePort, contentService)
				.synchronize(ACCESS, "user@example.com");

		verify(contentService, never()).backup(ACCESS, current);
		verify(metadataPort).save(new StoredFile("file-1", "user@example.com", "Report", "root", null,
				"text/plain", false, "revision-1", 7L));
	}

	@Test
	void reportsEnumerationTotalThenPerFileProgress() {
		DriveFileListingPort listingPort = mock(DriveFileListingPort.class);
		DriveChangePort changePort = mock(DriveChangePort.class);
		FileMetadataPort metadataPort = mock(FileMetadataPort.class);
		SyncStatePort statePort = mock(SyncStatePort.class);
		BackupProgressTracker progressTracker = mock(BackupProgressTracker.class);
		StoredFile first = new StoredFile("file-1", "user@example.com", "A", "root", null,
				"text/plain", false, "revision-1", null);
		StoredFile second = new StoredFile("file-2", "user@example.com", "B", "root", null,
				"text/plain", false, "revision-2", null);
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.empty());
		when(listingPort.listAllFiles(ACCESS, "user@example.com")).thenReturn(List.of(first, second));
		when(changePort.getStartPageToken(ACCESS, "user@example.com")).thenReturn("start-token");

		new InitialDriveSyncService(listingPort, changePort, metadataPort, statePort, null, progressTracker)
				.synchronize(ACCESS, "user@example.com");

		InOrder order = inOrder(progressTracker);
		order.verify(progressTracker).enumerating();
		order.verify(progressTracker).enumerated(2);
		order.verify(progressTracker).itemProcessed("A");
		order.verify(progressTracker).itemProcessed("B");
	}

	@Test
	void stopsProcessingRemainingFilesWhenImmediateStopIsRequestedAndNeverEstablishesABaseline() {
		DriveFileListingPort listingPort = mock(DriveFileListingPort.class);
		DriveChangePort changePort = mock(DriveChangePort.class);
		FileMetadataPort metadataPort = mock(FileMetadataPort.class);
		SyncStatePort statePort = mock(SyncStatePort.class);
		BackupCancellation cancellation = new BackupCancellation();
		StoredFile first = new StoredFile("file-1", "user@example.com", "A", "root", null,
				"text/plain", false, "revision-1", null);
		StoredFile second = new StoredFile("file-2", "user@example.com", "B", "root", null,
				"text/plain", false, "revision-2", null);
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.empty());
		when(listingPort.listAllFiles(ACCESS, "user@example.com")).thenReturn(List.of(first, second));
		doAnswer(invocation -> {
			cancellation.requestStop(BackupStopMode.IMMEDIATE);
			return null;
		}).when(metadataPort).save(first);

		InitialSyncResult result = new InitialDriveSyncService(listingPort, changePort, metadataPort, statePort,
				null, BackupProgressTracker.NO_OP, cancellation).synchronize(ACCESS, "user@example.com");

		assertEquals(new InitialSyncResult("user@example.com", 1, null), result);
		verify(metadataPort).save(first);
		verify(metadataPort, never()).save(second);
		verify(statePort, never()).save(any());
		verify(changePort, never()).getStartPageToken(any(), any());
	}
}
