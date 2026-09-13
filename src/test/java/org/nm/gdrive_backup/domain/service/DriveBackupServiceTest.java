package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.nm.gdrive_backup.domain.model.BackupMode;
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
	void runsFullInventoryAndClearsAnyExistingBaselineWhenModeIsFull() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		when(statePort.findByScopeKey("user@example.com"))
				.thenReturn(Optional.of(new SyncState("user@example.com", "old-token")));
		when(initialSync.synchronize(ACCESS, "user@example.com"))
				.thenReturn(new InitialSyncResult("user@example.com", 4, "token"));

		BackupResult result = new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity())
				.synchronize(ACCESS, "user@example.com", BackupMode.FULL);

		assertEquals(new BackupResult("user@example.com", 4, true), result);
		InOrder order = inOrder(statePort, initialSync);
		order.verify(statePort).deleteByScopeKey("user@example.com");
		order.verify(initialSync).synchronize(ACCESS, "user@example.com");
		verify(changeSync, never()).synchronize(ACCESS, "user@example.com");
	}

	@Test
	void runsFullInventoryWhenModeIsIncrementalButNoBaselineExists() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, "user@example.com"))
				.thenReturn(new InitialSyncResult("user@example.com", 4, "token"));

		BackupResult result = new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity())
				.synchronize(ACCESS, "user@example.com", BackupMode.INCREMENTAL);

		assertEquals(new BackupResult("user@example.com", 4, true), result);
		verify(initialSync).synchronize(ACCESS, "user@example.com");
	}

	@Test
	void usesChangesFeedWhenModeIsIncrementalAndBaselineExists() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		when(statePort.findByScopeKey("user@example.com"))
				.thenReturn(Optional.of(new SyncState("user@example.com", "old-token")));
		when(changeSync.synchronize(ACCESS, "user@example.com"))
				.thenReturn(new SyncResult("user@example.com", 2, "new-token"));

		BackupResult result = new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity())
				.synchronize(ACCESS, "user@example.com", BackupMode.INCREMENTAL);

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

		BackupResult result = new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity())
				.synchronize(ACCESS, "user@example.com", BackupMode.INCREMENTAL);

		assertEquals(new BackupResult("user@example.com", 5, true), result);
		verify(statePort).deleteByScopeKey("user@example.com");
		verify(initialSync).synchronize(ACCESS, "user@example.com");
	}

	@Test
	void marksBackupActiveOnlyWhileSynchronizing() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		BackupActivity activity = new BackupActivity();
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, "user@example.com")).thenAnswer(invocation -> {
			assertTrue(activity.isActive());
			return new InitialSyncResult("user@example.com", 1, "token");
		});

		new DriveBackupService(statePort, initialSync, mock(DriveChangeSyncUseCase.class), activity)
				.synchronize(ACCESS, "user@example.com", BackupMode.FULL);

		assertFalse(activity.isActive());
	}

	@Test
	void releasesBackupActivityWhenSynchronizationFails() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		BackupActivity activity = new BackupActivity();
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, "user@example.com")).thenThrow(new IllegalStateException("Drive failed"));

		DriveBackupService service = new DriveBackupService(statePort, initialSync,
				mock(DriveChangeSyncUseCase.class), activity);

		assertThrows(IllegalStateException.class,
				() -> service.synchronize(ACCESS, "user@example.com", BackupMode.FULL));
		assertFalse(activity.isActive());
	}
}
