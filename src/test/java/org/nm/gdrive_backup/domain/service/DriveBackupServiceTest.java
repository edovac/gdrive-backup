package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.BackupResult;
import org.nm.gdrive_backup.domain.model.InitialSyncResult;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.SyncResult;
import org.nm.gdrive_backup.domain.model.SyncState;
import org.nm.gdrive_backup.domain.model.StaleDrivePageTokenException;
import org.nm.gdrive_backup.domain.port.in.DriveChangeSyncUseCase;
import org.nm.gdrive_backup.domain.port.in.InitialDriveSyncUseCase;
import org.nm.gdrive_backup.domain.port.out.SyncStatePort;

class DriveBackupServiceTest {

	private static final ServiceAccountAccess ACCESS = new ServiceAccountAccess(
			UUID.randomUUID(), "user@example.com", Instant.now().plusSeconds(3600), Set.of("drive.readonly"));

	@Test
	void usesInitialInventoryWhenNoBaselineExists() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, "user@example.com"))
				.thenReturn(new InitialSyncResult("user@example.com", 4, "token"));

		BackupResult result = new DriveBackupService(statePort, initialSync, changeSync)
				.synchronize(ACCESS, "user@example.com");

		assertEquals(new BackupResult("user@example.com", 4, true), result);
		verify(initialSync).synchronize(ACCESS, "user@example.com");
	}

	@Test
	void usesChangesFeedWhenBaselineExists() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		when(statePort.findByScopeKey("user@example.com"))
				.thenReturn(Optional.of(new SyncState("user@example.com", "old-token")));
		when(changeSync.synchronize(ACCESS, "user@example.com"))
				.thenReturn(new SyncResult("user@example.com", 2, "new-token"));

		BackupResult result = new DriveBackupService(statePort, initialSync, changeSync)
				.synchronize(ACCESS, "user@example.com");

		assertEquals(new BackupResult("user@example.com", 2, false), result);
		verify(changeSync).synchronize(ACCESS, "user@example.com");
	}

	@Test
	void reInventoriesScopeWhenDriveChangeTokenHasExpired() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		when(statePort.findByScopeKey("user@example.com"))
				.thenReturn(Optional.of(new SyncState("user@example.com", "expired-token")));
		when(changeSync.synchronize(ACCESS, "user@example.com"))
				.thenThrow(new StaleDrivePageTokenException("expired", null));
		when(initialSync.synchronize(ACCESS, "user@example.com"))
				.thenReturn(new InitialSyncResult("user@example.com", 5, "fresh-token"));

		BackupResult result = new DriveBackupService(statePort, initialSync, changeSync)
				.synchronize(ACCESS, "user@example.com");

		assertEquals(new BackupResult("user@example.com", 5, true), result);
		verify(statePort).deleteByScopeKey("user@example.com");
		verify(initialSync).synchronize(ACCESS, "user@example.com");
	}
}
