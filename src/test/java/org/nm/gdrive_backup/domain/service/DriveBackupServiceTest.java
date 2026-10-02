package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.BackupMode;
import org.nm.gdrive_backup.domain.model.BackupResult;
import org.nm.gdrive_backup.domain.model.BackupStopMode;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.InitialSyncResult;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StaleDrivePageTokenException;
import org.nm.gdrive_backup.domain.model.SyncResult;
import org.nm.gdrive_backup.domain.model.SyncState;
import org.nm.gdrive_backup.domain.port.in.DriveChangeSyncUseCase;
import org.nm.gdrive_backup.domain.port.in.InitialDriveSyncUseCase;
import org.nm.gdrive_backup.domain.port.out.DriveMetadataPort;
import org.nm.gdrive_backup.domain.port.out.SyncStatePort;

class DriveBackupServiceTest {

	private static final ServiceAccountAccess ACCESS = new ServiceAccountAccess(
			UUID.randomUUID(), "user@example.com", Instant.now().plusSeconds(3600), Set.of("drive.readonly"));
	private static final DriveScope PERSONAL_SCOPE = DriveScope.personal("user@example.com");
	private static final DriveScope SHARED_SCOPE = DriveScope.sharedDrive("drive-1");

	@Test
	void runsFullInventoryRegardlessOfAnySavedBaselineWhenModeIsFull() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		when(statePort.findByScopeKey("user@example.com"))
				.thenReturn(Optional.of(new SyncState("user@example.com", "old-token")));
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE, null))
				.thenReturn(new InitialSyncResult(PERSONAL_SCOPE, 4, "token", null, false));

		BackupResult result = new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity())
				.synchronize(ACCESS, PERSONAL_SCOPE, BackupMode.FULL);

		assertEquals(new BackupResult(PERSONAL_SCOPE, 4, true, false), result);
		verify(initialSync).synchronize(ACCESS, PERSONAL_SCOPE, null);
		verify(changeSync, never()).synchronize(ACCESS, PERSONAL_SCOPE, null);
	}

	@Test
	void runsFullInventoryWhenModeIsIncrementalButNoBaselineExists() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE, null))
				.thenReturn(new InitialSyncResult(PERSONAL_SCOPE, 4, "token", null, false));

		BackupResult result = new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity())
				.synchronize(ACCESS, PERSONAL_SCOPE, BackupMode.INCREMENTAL);

		assertEquals(new BackupResult(PERSONAL_SCOPE, 4, true, false), result);
		verify(initialSync).synchronize(ACCESS, PERSONAL_SCOPE, null);
	}

	@Test
	void usesChangesFeedWhenModeIsIncrementalAndBaselineExists() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		when(statePort.findByScopeKey("user@example.com"))
				.thenReturn(Optional.of(new SyncState("user@example.com", "old-token")));
		when(changeSync.synchronize(ACCESS, PERSONAL_SCOPE, null))
				.thenReturn(new SyncResult(PERSONAL_SCOPE, 2, "new-token", null, false));

		BackupResult result = new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity())
				.synchronize(ACCESS, PERSONAL_SCOPE, BackupMode.INCREMENTAL);

		assertEquals(new BackupResult(PERSONAL_SCOPE, 2, false, false), result);
		verify(changeSync).synchronize(ACCESS, PERSONAL_SCOPE, null);
	}

	@Test
	void reInventoriesScopeWhenDriveChangeTokenHasExpired() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		when(statePort.findByScopeKey("user@example.com"))
				.thenReturn(Optional.of(new SyncState("user@example.com", "expired-token")));
		when(changeSync.synchronize(ACCESS, PERSONAL_SCOPE, null))
				.thenThrow(new StaleDrivePageTokenException("expired", null));
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE, null))
				.thenReturn(new InitialSyncResult(PERSONAL_SCOPE, 5, "fresh-token", null, false));

		BackupResult result = new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity())
				.synchronize(ACCESS, PERSONAL_SCOPE, BackupMode.INCREMENTAL);

		assertEquals(new BackupResult(PERSONAL_SCOPE, 5, true, false), result);
		verify(initialSync).synchronize(ACCESS, PERSONAL_SCOPE, null);
	}

	@Test
	void marksBackupActiveOnlyWhileSynchronizing() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		BackupActivity activity = new BackupActivity();
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE, null)).thenAnswer(invocation -> {
			assertTrue(activity.isActive());
			return new InitialSyncResult(PERSONAL_SCOPE, 1, "token", null, false);
		});

		new DriveBackupService(statePort, initialSync, mock(DriveChangeSyncUseCase.class), activity)
				.synchronize(ACCESS, PERSONAL_SCOPE, BackupMode.FULL);

		assertFalse(activity.isActive());
	}

	@Test
	void releasesBackupActivityWhenSynchronizationFails() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		BackupActivity activity = new BackupActivity();
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE, null)).thenThrow(new IllegalStateException("Drive failed"));

		DriveBackupService service = new DriveBackupService(statePort, initialSync,
				mock(DriveChangeSyncUseCase.class), activity);

		assertThrows(IllegalStateException.class,
				() -> service.synchronize(ACCESS, PERSONAL_SCOPE, BackupMode.FULL));
		assertFalse(activity.isActive());
	}

	@Test
	void synchronizesOnlyTheSelectedDrives() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		DriveMetadataPort driveMetadataPort = mock(DriveMetadataPort.class);
		when(statePort.findByScopeKey(anyString())).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE, "My Drive"))
				.thenReturn(new InitialSyncResult(PERSONAL_SCOPE, 4, "token", null, false));
		when(initialSync.synchronize(ACCESS, SHARED_SCOPE, "Finance"))
				.thenReturn(new InitialSyncResult(SHARED_SCOPE, 2, "token-1", null, false));
		List<AvailableDrive> selection = List.of(
				new AvailableDrive("root", "My Drive", false),
				new AvailableDrive("drive-1", "Finance", true));

		List<BackupResult> results = new DriveBackupService(statePort, initialSync, changeSync,
				new BackupActivity(), driveMetadataPort)
				.synchronizeSelectedDrives(ACCESS, selection, BackupMode.INCREMENTAL);

		assertEquals(List.of(
				new BackupResult(PERSONAL_SCOPE, 4, true, false),
				new BackupResult(SHARED_SCOPE, 2, true, false)), results);
		verify(driveMetadataPort).save(argThat(drive ->
				drive.driveId().equals("drive-1") && drive.name().equals("Finance")));
		verify(driveMetadataPort, times(1)).save(any());
	}

	@Test
	void synchronizingASingleSharedDriveLeavesThePersonalScopeUntouched() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		DriveMetadataPort driveMetadataPort = mock(DriveMetadataPort.class);
		when(statePort.findByScopeKey("drive-1")).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, SHARED_SCOPE, "Finance"))
				.thenReturn(new InitialSyncResult(SHARED_SCOPE, 2, "token-1", null, false));

		List<BackupResult> results = new DriveBackupService(statePort, initialSync, changeSync,
				new BackupActivity(), driveMetadataPort)
				.synchronizeSelectedDrives(ACCESS, List.of(new AvailableDrive("drive-1", "Finance", true)),
						BackupMode.INCREMENTAL);

		assertEquals(List.of(new BackupResult(SHARED_SCOPE, 2, true, false)), results);
		verify(initialSync, never()).synchronize(ACCESS, PERSONAL_SCOPE, "My Drive");
		verify(changeSync, never()).synchronize(ACCESS, PERSONAL_SCOPE, "My Drive");
	}

	@Test
	void rejectsAnEmptySelection() {
		DriveBackupService service = new DriveBackupService(mock(SyncStatePort.class),
				mock(InitialDriveSyncUseCase.class), mock(DriveChangeSyncUseCase.class), new BackupActivity());

		assertThrows(IllegalArgumentException.class,
				() -> service.synchronizeSelectedDrives(ACCESS, List.of(), BackupMode.INCREMENTAL));
	}

	@Test
	void keepsBackupActivityActiveForTheEntireSelectionRun() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		DriveMetadataPort driveMetadataPort = mock(DriveMetadataPort.class);
		BackupActivity activity = new BackupActivity();
		when(statePort.findByScopeKey(anyString())).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE, "My Drive"))
				.thenReturn(new InitialSyncResult(PERSONAL_SCOPE, 4, "token", null, false));
		when(initialSync.synchronize(ACCESS, SHARED_SCOPE, "Finance")).thenAnswer(invocation -> {
			assertTrue(activity.isActive());
			return new InitialSyncResult(SHARED_SCOPE, 2, "token-1", null, false);
		});
		List<AvailableDrive> selection = List.of(
				new AvailableDrive("root", "My Drive", false),
				new AvailableDrive("drive-1", "Finance", true));

		new DriveBackupService(statePort, initialSync, changeSync, activity, driveMetadataPort)
				.synchronizeSelectedDrives(ACCESS, selection, BackupMode.INCREMENTAL);

		assertFalse(activity.isActive());
	}

	@Test
	void skipsDriveMetadataBookkeepingWhenPortIsUnavailable() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		when(statePort.findByScopeKey("drive-1")).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, SHARED_SCOPE, "Finance"))
				.thenReturn(new InitialSyncResult(SHARED_SCOPE, 2, "token-1", null, false));

		List<BackupResult> results = new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity())
				.synchronizeSelectedDrives(ACCESS, List.of(new AvailableDrive("drive-1", "Finance", true)),
						BackupMode.INCREMENTAL);

		assertEquals(List.of(new BackupResult(SHARED_SCOPE, 2, true, false)), results);
	}

	@Test
	void reportsJobAndPerDriveProgressForASelection() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		BackupProgressTracker progressTracker = mock(BackupProgressTracker.class);
		when(statePort.findByScopeKey(anyString())).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE, "My Drive"))
				.thenReturn(new InitialSyncResult(PERSONAL_SCOPE, 4, "token", null, false));
		when(initialSync.synchronize(ACCESS, SHARED_SCOPE, "Finance"))
				.thenReturn(new InitialSyncResult(SHARED_SCOPE, 2, "token-1", null, false));
		List<AvailableDrive> selection = List.of(
				new AvailableDrive("root", "My Drive", false),
				new AvailableDrive("drive-1", "Finance", true));

		new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity(), null, progressTracker)
				.synchronizeSelectedDrives(ACCESS, selection, BackupMode.INCREMENTAL);

		InOrder order = inOrder(progressTracker);
		order.verify(progressTracker).jobStarted(selection);
		order.verify(progressTracker).driveStarted(selection.get(0));
		order.verify(progressTracker).driveCompleted();
		order.verify(progressTracker).driveStarted(selection.get(1));
		order.verify(progressTracker).driveCompleted();
		order.verify(progressTracker).jobFinished();
	}

	@Test
	void keepsGoingAfterADriveFailsAndReportsIt() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		DriveMetadataPort driveMetadataPort = mock(DriveMetadataPort.class);
		BackupProgressTracker progressTracker = mock(BackupProgressTracker.class);
		DriveScope otherShared = DriveScope.sharedDrive("drive-2");
		when(statePort.findByScopeKey(anyString())).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE, "My Drive"))
				.thenReturn(new InitialSyncResult(PERSONAL_SCOPE, 4, "token", null, false));
		when(initialSync.synchronize(ACCESS, SHARED_SCOPE, "Finance"))
				.thenThrow(new IllegalStateException("No access"));
		when(initialSync.synchronize(ACCESS, otherShared, "Marketing"))
				.thenReturn(new InitialSyncResult(otherShared, 1, "token-2", null, false));
		List<AvailableDrive> selection = List.of(
				new AvailableDrive("root", "My Drive", false),
				new AvailableDrive("drive-1", "Finance", true),
				new AvailableDrive("drive-2", "Marketing", true));

		List<BackupResult> results = new DriveBackupService(statePort, initialSync, changeSync,
				new BackupActivity(), driveMetadataPort, progressTracker)
				.synchronizeSelectedDrives(ACCESS, selection, BackupMode.FULL);

		assertEquals(List.of(
				new BackupResult(PERSONAL_SCOPE, 4, true, false),
				BackupResult.failed(SHARED_SCOPE, "No access"),
				new BackupResult(otherShared, 1, true, false)), results);
		verify(driveMetadataPort, never()).save(argThat(drive -> drive.driveId().equals("drive-1")));
		verify(driveMetadataPort).save(argThat(drive -> drive.driveId().equals("drive-2")));
		verify(progressTracker).driveFailed();
		verify(progressTracker, times(2)).driveCompleted();
		verify(progressTracker).jobFinished();
	}

	@Test
	void reportsEveryDriveAsFailedWhenAllOfThemFail() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		when(statePort.findByScopeKey(anyString())).thenReturn(Optional.empty());
		when(initialSync.synchronize(any(), any(), any())).thenThrow(new IllegalStateException());

		List<BackupResult> results = new DriveBackupService(statePort, initialSync,
				mock(DriveChangeSyncUseCase.class), new BackupActivity())
				.synchronizeSelectedDrives(ACCESS, List.of(new AvailableDrive("root", "My Drive", false)),
						BackupMode.FULL);

		assertEquals(List.of(BackupResult.failed(PERSONAL_SCOPE, "IllegalStateException")), results);
	}

	@Test
	void stopsBeforeTheNextDriveWhenImmediateStopIsRequestedDuringTheFirst() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		BackupCancellation cancellation = new BackupCancellation();
		when(statePort.findByScopeKey(anyString())).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE, "My Drive")).thenAnswer(invocation -> {
			cancellation.requestStop(BackupStopMode.IMMEDIATE);
			return new InitialSyncResult(PERSONAL_SCOPE, 4, null, null, true);
		});
		List<AvailableDrive> selection = List.of(
				new AvailableDrive("root", "My Drive", false),
				new AvailableDrive("drive-1", "Finance", true));

		List<BackupResult> results = new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity(),
				null, BackupProgressTracker.NO_OP, cancellation)
				.synchronizeSelectedDrives(ACCESS, selection, BackupMode.INCREMENTAL);

		assertEquals(List.of(new BackupResult(PERSONAL_SCOPE, 4, true, true)), results);
		verify(initialSync, never()).synchronize(ACCESS, SHARED_SCOPE, "Finance");
	}

	@Test
	void stopsBeforeTheNextDriveWhenAfterCurrentDriveStopIsRequestedButLeavesTheCurrentDriveUncancelled() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		BackupCancellation cancellation = new BackupCancellation();
		when(statePort.findByScopeKey(anyString())).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE, "My Drive")).thenAnswer(invocation -> {
			cancellation.requestStop(BackupStopMode.AFTER_CURRENT_DRIVE);
			return new InitialSyncResult(PERSONAL_SCOPE, 4, "token", null, false);
		});
		List<AvailableDrive> selection = List.of(
				new AvailableDrive("root", "My Drive", false),
				new AvailableDrive("drive-1", "Finance", true));

		List<BackupResult> results = new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity(),
				null, BackupProgressTracker.NO_OP, cancellation)
				.synchronizeSelectedDrives(ACCESS, selection, BackupMode.INCREMENTAL);

		assertEquals(List.of(new BackupResult(PERSONAL_SCOPE, 4, true, false)), results);
		verify(initialSync, never()).synchronize(ACCESS, SHARED_SCOPE, "Finance");
	}

	@Test
	void passesTheDriveDisplayNameToTheSyncSoItCanNameTheArchiveFolder() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		when(statePort.findByScopeKey(anyString())).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, SHARED_SCOPE, "Finance"))
				.thenReturn(new InitialSyncResult(SHARED_SCOPE, 2, "token-1", null, false));

		new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity())
				.synchronizeSelectedDrives(ACCESS, List.of(new AvailableDrive("drive-1", "Finance", true)),
						BackupMode.INCREMENTAL);

		verify(initialSync).synchronize(ACCESS, SHARED_SCOPE, "Finance");
	}

	@Test
	void keepsTheExistingBaselineSoAFailedFullRunDoesNotLoseTheChain() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE, null)).thenThrow(new IllegalStateException("Drive failed"));
		DriveBackupService service = new DriveBackupService(statePort, initialSync,
				mock(DriveChangeSyncUseCase.class), new BackupActivity());

		assertThrows(IllegalStateException.class, () -> service.synchronize(ACCESS, PERSONAL_SCOPE, BackupMode.FULL));

		verify(statePort, never()).deleteByScopeKey(any());
	}

	@Test
	void reportsACancelledResultFromTheSyncService() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE, null))
				.thenReturn(new InitialSyncResult(PERSONAL_SCOPE, 1, null, null, true));

		BackupResult result = new DriveBackupService(statePort, initialSync, mock(DriveChangeSyncUseCase.class),
				new BackupActivity()).synchronize(ACCESS, PERSONAL_SCOPE, BackupMode.FULL);

		assertEquals(new BackupResult(PERSONAL_SCOPE, 1, true, true), result);
	}
}
