package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.nm.gdrive_backup.domain.model.InitialSyncResult;
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
}