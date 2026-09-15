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
import org.nm.gdrive_backup.domain.model.BackupLocation;
import org.nm.gdrive_backup.domain.model.LocationStatus;
import org.nm.gdrive_backup.domain.model.LocationValidation;
import org.nm.gdrive_backup.domain.port.out.BackupLocationPort;

class BackupLocationServiceTest {

	private static final Path ROOT = Path.of("backups", "current").toAbsolutePath().normalize();
	private static final BackupLocation CURRENT = new BackupLocation(ROOT);

	@Test
	void reportsActiveRootAsUnchangedWithoutCheckingIt() {
		BackupLocationPort port = mock(BackupLocationPort.class);
		when(port.activeLocation()).thenReturn(CURRENT);

		LocationValidation validation = new BackupLocationService(port, new BackupActivity())
				.validateRoot(ROOT);

		assertEquals(LocationStatus.UNCHANGED, validation.status());
		verify(port, never()).checkRoot(any());
	}

	@Test
	void changingToTheActiveRootAppliesNothing() {
		BackupLocationPort port = mock(BackupLocationPort.class);
		when(port.activeLocation()).thenReturn(CURRENT);

		BackupLocation result = new BackupLocationService(port, new BackupActivity())
				.changeRoot(Path.of("backups", "..", "backups", "current"));

		assertEquals(CURRENT, result);
		verify(port, never()).applyRoot(any());
	}

	@Test
	void rejectsInvalidRootWithoutApplyingIt() {
		BackupLocationPort port = mock(BackupLocationPort.class);
		Path candidate = ROOT.resolveSibling("read-only");
		when(port.activeLocation()).thenReturn(CURRENT);
		when(port.checkRoot(candidate))
				.thenReturn(new LocationValidation(LocationStatus.INVALID, "Directory is not writable"));

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> new BackupLocationService(port, new BackupActivity()).changeRoot(candidate));

		assertEquals("Directory is not writable", error.getMessage());
		verify(port, never()).applyRoot(any());
	}

	@Test
	void appliesValidRootAndReturnsTheNewLocation() {
		BackupLocationPort port = mock(BackupLocationPort.class);
		Path candidate = ROOT.resolveSibling("archive");
		BackupLocation updated = new BackupLocation(candidate);
		when(port.activeLocation()).thenReturn(CURRENT, updated);
		when(port.checkRoot(candidate))
				.thenReturn(new LocationValidation(LocationStatus.NEW, "The directory and a new backup history will be created"));

		BackupLocation result = new BackupLocationService(port, new BackupActivity()).changeRoot(candidate);

		assertEquals(updated, result);
		verify(port).applyRoot(candidate);
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
					.changeRoot(ROOT.resolveSibling("archive")));
			verify(port, never()).applyRoot(any());
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
