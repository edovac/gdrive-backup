package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.DriveChange;
import org.nm.gdrive_backup.domain.model.DriveChangePage;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.SyncResult;
import org.nm.gdrive_backup.domain.model.SyncState;
import org.nm.gdrive_backup.domain.port.out.DriveChangePort;
import org.nm.gdrive_backup.domain.port.out.SyncStatePort;

class DriveChangeSyncServiceTest {

	private static final ServiceAccountAccess ACCESS = new ServiceAccountAccess(
			UUID.randomUUID(), "user@example.com", Instant.now().plusSeconds(3600), Set.of("drive.readonly"));

	@Test
	void drainsPagesAndPersistsNewStartToken() {
		DriveChangePort changePort = mock(DriveChangePort.class);
		SyncStatePort statePort = mock(SyncStatePort.class);
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.of(
				new SyncState("user@example.com", "old-token")));
		when(changePort.listChanges(ACCESS, "user@example.com", "old-token"))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-1", false, null)), "next-token", null));
		when(changePort.listChanges(ACCESS, "user@example.com", "next-token"))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-2", true, null)), null, "new-token"));
		DriveChangeSyncService service = new DriveChangeSyncService(changePort, statePort);

		SyncResult result = service.synchronize(ACCESS, "user@example.com");

		assertEquals(new SyncResult("user@example.com", 2, "new-token"), result);
		verify(statePort).save(new SyncState("user@example.com", "new-token"));
	}

	@Test
	void establishesStartTokenForAnUninitializedScope() {
		DriveChangePort changePort = mock(DriveChangePort.class);
		SyncStatePort statePort = mock(SyncStatePort.class);
		when(statePort.findByScopeKey("drive-1")).thenReturn(Optional.empty());
		when(changePort.getStartPageToken(ACCESS, "drive-1")).thenReturn("start-token");
		when(changePort.listChanges(ACCESS, "drive-1", "start-token"))
				.thenReturn(new DriveChangePage(List.of(), null, "new-token"));

		SyncResult result = new DriveChangeSyncService(changePort, statePort).synchronize(ACCESS, "drive-1");

		assertEquals(new SyncResult("drive-1", 0, "new-token"), result);
		verify(statePort).save(new SyncState("drive-1", "new-token"));
	}
}