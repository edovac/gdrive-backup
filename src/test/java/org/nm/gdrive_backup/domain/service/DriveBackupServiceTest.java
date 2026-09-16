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
import org.nm.gdrive_backup.domain.model.StoredDrive;
import org.nm.gdrive_backup.domain.model.SyncResult;
import org.nm.gdrive_backup.domain.model.SyncState;
import org.nm.gdrive_backup.domain.model.StaleDrivePageTokenException;
import org.nm.gdrive_backup.domain.port.in.ArchivePackagingUseCase;
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
	void runsFullInventoryAndClearsAnyExistingBaselineWhenModeIsFull() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		when(statePort.findByScopeKey("user@example.com"))
				.thenReturn(Optional.of(new SyncState("user@example.com", "old-token")));
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE))
				.thenReturn(new InitialSyncResult(PERSONAL_SCOPE, 4, "token"));

		BackupResult result = new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity())
				.synchronize(ACCESS, PERSONAL_SCOPE, BackupMode.FULL);

		assertEquals(new BackupResult(PERSONAL_SCOPE, 4, true, false), result);
		InOrder order = inOrder(statePort, initialSync);
		order.verify(statePort).deleteByScopeKey("user@example.com");
		order.verify(initialSync).synchronize(ACCESS, PERSONAL_SCOPE);
		verify(changeSync, never()).synchronize(ACCESS, PERSONAL_SCOPE);
	}

	@Test
	void runsFullInventoryWhenModeIsIncrementalButNoBaselineExists() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE))
				.thenReturn(new InitialSyncResult(PERSONAL_SCOPE, 4, "token"));

		BackupResult result = new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity())
				.synchronize(ACCESS, PERSONAL_SCOPE, BackupMode.INCREMENTAL);

		assertEquals(new BackupResult(PERSONAL_SCOPE, 4, true, false), result);
		verify(initialSync).synchronize(ACCESS, PERSONAL_SCOPE);
	}

	@Test
	void usesChangesFeedWhenModeIsIncrementalAndBaselineExists() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		when(statePort.findByScopeKey("user@example.com"))
				.thenReturn(Optional.of(new SyncState("user@example.com", "old-token")));
		when(changeSync.synchronize(ACCESS, PERSONAL_SCOPE))
				.thenReturn(new SyncResult(PERSONAL_SCOPE, 2, "old-token", "new-token", List.of(), List.of()));

		BackupResult result = new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity())
				.synchronize(ACCESS, PERSONAL_SCOPE, BackupMode.INCREMENTAL);

		assertEquals(new BackupResult(PERSONAL_SCOPE, 2, false, false), result);
		verify(changeSync).synchronize(ACCESS, PERSONAL_SCOPE);
	}

	@Test
	void reInventoriesScopeWhenDriveChangeTokenHasExpired() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		when(statePort.findByScopeKey("user@example.com"))
				.thenReturn(Optional.of(new SyncState("user@example.com", "expired-token")));
		when(changeSync.synchronize(ACCESS, PERSONAL_SCOPE))
				.thenThrow(new StaleDrivePageTokenException("expired", null));
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE))
				.thenReturn(new InitialSyncResult(PERSONAL_SCOPE, 5, "fresh-token"));

		BackupResult result = new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity())
				.synchronize(ACCESS, PERSONAL_SCOPE, BackupMode.INCREMENTAL);

		assertEquals(new BackupResult(PERSONAL_SCOPE, 5, true, false), result);
		verify(statePort).deleteByScopeKey("user@example.com");
		verify(initialSync).synchronize(ACCESS, PERSONAL_SCOPE);
	}

	@Test
	void marksBackupActiveOnlyWhileSynchronizing() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		BackupActivity activity = new BackupActivity();
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE)).thenAnswer(invocation -> {
			assertTrue(activity.isActive());
			return new InitialSyncResult(PERSONAL_SCOPE, 1, "token");
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
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE)).thenThrow(new IllegalStateException("Drive failed"));

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
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE))
				.thenReturn(new InitialSyncResult(PERSONAL_SCOPE, 4, "token"));
		when(initialSync.synchronize(ACCESS, SHARED_SCOPE))
				.thenReturn(new InitialSyncResult(SHARED_SCOPE, 2, "token-1"));
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
		when(initialSync.synchronize(ACCESS, SHARED_SCOPE))
				.thenReturn(new InitialSyncResult(SHARED_SCOPE, 2, "token-1"));

		List<BackupResult> results = new DriveBackupService(statePort, initialSync, changeSync,
				new BackupActivity(), driveMetadataPort)
				.synchronizeSelectedDrives(ACCESS, List.of(new AvailableDrive("drive-1", "Finance", true)),
						BackupMode.INCREMENTAL);

		assertEquals(List.of(new BackupResult(SHARED_SCOPE, 2, true, false)), results);
		verify(initialSync, never()).synchronize(ACCESS, PERSONAL_SCOPE);
		verify(changeSync, never()).synchronize(ACCESS, PERSONAL_SCOPE);
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
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE))
				.thenReturn(new InitialSyncResult(PERSONAL_SCOPE, 4, "token"));
		when(initialSync.synchronize(ACCESS, SHARED_SCOPE)).thenAnswer(invocation -> {
			assertTrue(activity.isActive());
			return new InitialSyncResult(SHARED_SCOPE, 2, "token-1");
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
		when(initialSync.synchronize(ACCESS, SHARED_SCOPE))
				.thenReturn(new InitialSyncResult(SHARED_SCOPE, 2, "token-1"));

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
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE))
				.thenReturn(new InitialSyncResult(PERSONAL_SCOPE, 4, "token"));
		when(initialSync.synchronize(ACCESS, SHARED_SCOPE))
				.thenReturn(new InitialSyncResult(SHARED_SCOPE, 2, "token-1"));
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
	void stopsBeforeTheNextDriveWhenImmediateStopIsRequestedDuringTheFirst() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		BackupCancellation cancellation = new BackupCancellation();
		when(statePort.findByScopeKey(anyString())).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE)).thenAnswer(invocation -> {
			cancellation.requestStop(BackupStopMode.IMMEDIATE);
			return new InitialSyncResult(PERSONAL_SCOPE, 4, "token");
		});
		List<AvailableDrive> selection = List.of(
				new AvailableDrive("root", "My Drive", false),
				new AvailableDrive("drive-1", "Finance", true));

		List<BackupResult> results = new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity(),
				null, BackupProgressTracker.NO_OP, cancellation)
				.synchronizeSelectedDrives(ACCESS, selection, BackupMode.INCREMENTAL);

		assertEquals(List.of(new BackupResult(PERSONAL_SCOPE, 4, true, true)), results);
		verify(initialSync, never()).synchronize(ACCESS, SHARED_SCOPE);
	}

	@Test
	void stopsBeforeTheNextDriveWhenAfterCurrentDriveStopIsRequestedButLeavesTheCurrentDriveUncancelled() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		BackupCancellation cancellation = new BackupCancellation();
		when(statePort.findByScopeKey(anyString())).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE)).thenAnswer(invocation -> {
			cancellation.requestStop(BackupStopMode.AFTER_CURRENT_DRIVE);
			return new InitialSyncResult(PERSONAL_SCOPE, 4, "token");
		});
		List<AvailableDrive> selection = List.of(
				new AvailableDrive("root", "My Drive", false),
				new AvailableDrive("drive-1", "Finance", true));

		List<BackupResult> results = new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity(),
				null, BackupProgressTracker.NO_OP, cancellation)
				.synchronizeSelectedDrives(ACCESS, selection, BackupMode.INCREMENTAL);

		assertEquals(List.of(new BackupResult(PERSONAL_SCOPE, 4, true, false)), results);
		verify(initialSync, never()).synchronize(ACCESS, SHARED_SCOPE);
	}

	@Test
	void packagesAFullArchiveAfterASuccessfulFullInventoryWithNoDisplayNameForTheSingleScopeEntryPoint() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		ArchivePackagingUseCase archivePackagingUseCase = mock(ArchivePackagingUseCase.class);
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE)).thenReturn(new InitialSyncResult(PERSONAL_SCOPE, 4, "token"));

		new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity(), null,
				BackupProgressTracker.NO_OP, new BackupCancellation(), archivePackagingUseCase)
				.synchronize(ACCESS, PERSONAL_SCOPE, BackupMode.FULL);

		verify(archivePackagingUseCase).packageFullArchive(PERSONAL_SCOPE, null);
	}

	@Test
	void packagesAnIncrementalArchiveAfterASuccessfulIncrementalSync() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		ArchivePackagingUseCase archivePackagingUseCase = mock(ArchivePackagingUseCase.class);
		when(statePort.findByScopeKey("user@example.com"))
				.thenReturn(Optional.of(new SyncState("user@example.com", "old-token")));
		SyncResult syncResult = new SyncResult(PERSONAL_SCOPE, 2, "old-token", "new-token", List.of(), List.of());
		when(changeSync.synchronize(ACCESS, PERSONAL_SCOPE)).thenReturn(syncResult);

		new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity(), null,
				BackupProgressTracker.NO_OP, new BackupCancellation(), archivePackagingUseCase)
				.synchronize(ACCESS, PERSONAL_SCOPE, BackupMode.INCREMENTAL);

		verify(archivePackagingUseCase).packageIncrementalArchive(PERSONAL_SCOPE, null, syncResult);
	}

	@Test
	void reportsThePackagingPhaseBeforeInvokingTheUseCase() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		ArchivePackagingUseCase archivePackagingUseCase = mock(ArchivePackagingUseCase.class);
		BackupProgressTracker progressTracker = mock(BackupProgressTracker.class);
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE)).thenReturn(new InitialSyncResult(PERSONAL_SCOPE, 4, "token"));

		new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity(), null,
				progressTracker, new BackupCancellation(), archivePackagingUseCase)
				.synchronize(ACCESS, PERSONAL_SCOPE, BackupMode.FULL);

		InOrder order = inOrder(progressTracker, archivePackagingUseCase);
		order.verify(progressTracker).packaging();
		order.verify(archivePackagingUseCase).packageFullArchive(PERSONAL_SCOPE, null);
	}

	@Test
	void skipsPackagingWhenTheRunWasCancelled() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		ArchivePackagingUseCase archivePackagingUseCase = mock(ArchivePackagingUseCase.class);
		BackupCancellation cancellation = new BackupCancellation();
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, PERSONAL_SCOPE)).thenAnswer(invocation -> {
			cancellation.requestStop(BackupStopMode.IMMEDIATE);
			return new InitialSyncResult(PERSONAL_SCOPE, 4, "token");
		});

		new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity(), null,
				BackupProgressTracker.NO_OP, cancellation, archivePackagingUseCase)
				.synchronize(ACCESS, PERSONAL_SCOPE, BackupMode.FULL);

		verify(archivePackagingUseCase, never()).packageFullArchive(any(), any());
	}

	@Test
	void passesTheSharedDriveDisplayNameToPackagingWhenSynchronizingSelectedDrives() {
		SyncStatePort statePort = mock(SyncStatePort.class);
		InitialDriveSyncUseCase initialSync = mock(InitialDriveSyncUseCase.class);
		DriveChangeSyncUseCase changeSync = mock(DriveChangeSyncUseCase.class);
		ArchivePackagingUseCase archivePackagingUseCase = mock(ArchivePackagingUseCase.class);
		when(statePort.findByScopeKey(anyString())).thenReturn(Optional.empty());
		when(initialSync.synchronize(ACCESS, SHARED_SCOPE)).thenReturn(new InitialSyncResult(SHARED_SCOPE, 2, "token-1"));

		new DriveBackupService(statePort, initialSync, changeSync, new BackupActivity(), null,
				BackupProgressTracker.NO_OP, new BackupCancellation(), archivePackagingUseCase)
				.synchronizeSelectedDrives(ACCESS, List.of(new AvailableDrive("drive-1", "Finance", true)),
						BackupMode.INCREMENTAL);

		verify(archivePackagingUseCase).packageFullArchive(SHARED_SCOPE, "Finance");
	}
}
