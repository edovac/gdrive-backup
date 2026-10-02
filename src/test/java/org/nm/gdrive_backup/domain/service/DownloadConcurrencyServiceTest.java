package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DownloadConcurrencyServiceTest {

	private final BackupActivity activity = new BackupActivity();
	private final DownloadConcurrencyService service = new DownloadConcurrencyService(4, activity);

	@Test
	void startsAtTheConfiguredValueAndExposesTheAllowedRange() {
		assertEquals(4, service.current());
		assertEquals(DownloadConcurrencyService.MINIMUM, service.minimum());
		assertEquals(DownloadConcurrencyService.MAXIMUM, service.maximum());
		assertEquals(1, service.minimum());
		assertEquals(16, service.maximum());
	}

	@Test
	void changesTheValueWithinTheRange() {
		service.change(8);
		assertEquals(8, service.current());

		service.change(service.minimum());
		assertEquals(1, service.current());

		service.change(service.maximum());
		assertEquals(16, service.current());
	}

	@Test
	void rejectsValuesOutsideTheRangeAndKeepsTheCurrentOne() {
		assertThrows(IllegalArgumentException.class, () -> service.change(0));
		assertThrows(IllegalArgumentException.class, () -> service.change(-1));
		assertThrows(IllegalArgumentException.class, () -> service.change(17));

		assertEquals(4, service.current());
	}

	@Test
	void rejectsAnInitialValueOutsideTheRange() {
		assertThrows(IllegalArgumentException.class, () -> new DownloadConcurrencyService(0, activity));
		assertThrows(IllegalArgumentException.class, () -> new DownloadConcurrencyService(17, activity));
	}

	@Test
	void refusesToChangeWhileABackupIsRunning() {
		IllegalStateException refused = assertThrows(IllegalStateException.class,
				() -> activity.duringBackup(() -> {
					service.change(8);
					return null;
				}));

		assertEquals("Backup locations can't change while a backup is running", refused.getMessage());
		assertEquals(4, service.current());
	}

	@Test
	void canChangeAgainOnceTheBackupHasFinished() {
		activity.duringBackup(() -> service.current());

		service.change(2);

		assertEquals(2, service.current());
	}
}
