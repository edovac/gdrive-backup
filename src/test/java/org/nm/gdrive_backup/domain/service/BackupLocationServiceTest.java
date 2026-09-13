package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.BackupLocations;
import org.nm.gdrive_backup.domain.model.LocationStatus;
import org.nm.gdrive_backup.domain.model.LocationValidation;
import org.nm.gdrive_backup.domain.port.out.BackupLocationPort;

class BackupLocationServiceTest {

	private static final Path DESTINATION = Path.of("backups", "current").toAbsolutePath().normalize();
	private static final Path DATABASE = Path.of("backups", "current.db").toAbsolutePath().normalize();
	private static final BackupLocations CURRENT = new BackupLocations(DESTINATION, DATABASE);

	@Test
	void reportsActiveDestinationAsUnchangedWithoutCheckingIt() {
		BackupLocationPort port = mock(BackupLocationPort.class);
		when(port.activeLocations()).thenReturn(CURRENT);

		LocationValidation validation = new BackupLocationService(port, new BackupActivity())
				.validateBackupDestination(DESTINATION);

		assertEquals(LocationStatus.UNCHANGED, validation.status());
		verify(port, never()).checkBackupDestination(any());
	}

	@Test
	void changingToTheActiveDatabaseAppliesNothing() {
		BackupLocationPort port = mock(BackupLocationPort.class);
		when(port.activeLocations()).thenReturn(CURRENT);

		BackupLocations result = new BackupLocationService(port, new BackupActivity())
				.changeDatabaseFile(Path.of("backups", "..", "backups", "current.db"));

		assertEquals(CURRENT, result);
		verify(port, never()).applyDatabaseFile(any());
	}

	@Test
	void rejectsInvalidDestinationWithoutApplyingIt() {
		BackupLocationPort port = mock(BackupLocationPort.class);
		Path destination = DESTINATION.resolveSibling("read-only");
		when(port.activeLocations()).thenReturn(CURRENT);
		when(port.checkBackupDestination(destination))
				.thenReturn(new LocationValidation(LocationStatus.INVALID, "Directory is not writable"));

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> new BackupLocationService(port, new BackupActivity()).changeBackupDestination(destination));

		assertEquals("Directory is not writable", error.getMessage());
		verify(port, never()).applyBackupDestination(any());
	}

	@Test
	void appliesValidDatabaseFileAndReturnsTheNewLocations() {
		BackupLocationPort port = mock(BackupLocationPort.class);
		Path databaseFile = DATABASE.resolveSibling("archive.db");
		BackupLocations updated = new BackupLocations(DESTINATION, databaseFile);
		when(port.activeLocations()).thenReturn(CURRENT, updated);
		when(port.checkDatabaseFile(databaseFile))
				.thenReturn(new LocationValidation(LocationStatus.NEW, "A new, empty backup history will be created"));

		BackupLocations result = new BackupLocationService(port, new BackupActivity()).changeDatabaseFile(databaseFile);

		assertEquals(updated, result);
		verify(port).applyDatabaseFile(databaseFile);
	}

	@Test
	void refusesLocationChangesWhileABackupRuns() throws Exception {
		BackupLocationPort port = mock(BackupLocationPort.class);
		BackupActivity activity = new BackupActivity();
		CountDownLatch backupStarted = new CountDownLatch(1);
		CountDownLatch finishBackup = new CountDownLatch(1);
		Thread backup = new Thread(() -> activity.duringBackup(() -> {
			backupStarted.countDown();
			awaitQuietly(finishBackup);
			return null;
		}));
		backup.start();
		backupStarted.await();

		try {
			assertThrows(IllegalStateException.class, () -> new BackupLocationService(port, activity)
					.changeDatabaseFile(DATABASE.resolveSibling("archive.db")));
			verify(port, never()).applyDatabaseFile(any());
		} finally {
			finishBackup.countDown();
			backup.join();
		}
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}
}
