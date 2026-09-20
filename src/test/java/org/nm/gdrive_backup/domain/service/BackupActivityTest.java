package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BackupActivityTest {

	private final BackupActivity activity = new BackupActivity();

	@Test
	void backupsMayRunTogether() {
		int result = activity.duringBackup(() -> activity.duringBackup(() -> 7));

		assertEquals(7, result);
	}

	@Test
	void anExclusiveOperationRunsAloneAndMarksTheActivityActive() {
		boolean active = activity.duringExclusiveOperation(activity::isActive);

		assertTrue(active);
		assertFalse(activity.isActive());
	}

	@Test
	void anExclusiveOperationRefusesToStartBesideABackup() {
		assertThrows(IllegalStateException.class,
				() -> activity.duringBackup(() -> activity.duringExclusiveOperation(() -> 1)));
	}

	@Test
	void anExclusiveOperationRefusesToStartBesideAnotherOne() {
		assertThrows(IllegalStateException.class,
				() -> activity.duringExclusiveOperation(() -> activity.duringExclusiveOperation(() -> 1)));
	}

	@Test
	void aBackupRefusesToStartBesideAnExclusiveOperation() {
		assertThrows(IllegalStateException.class,
				() -> activity.duringExclusiveOperation(() -> activity.duringBackup(() -> 1)));
	}

	@Test
	void everythingIsReleasedAfterAnExceptionSoTheNextOperationCanRun() {
		assertThrows(IllegalArgumentException.class, () -> activity.duringExclusiveOperation(() -> {
			throw new IllegalArgumentException("boom");
		}));

		assertEquals(3, activity.duringExclusiveOperation(() -> 3));
		assertEquals(4, activity.duringBackup(() -> 4));
	}

	@Test
	void locationsCannotChangeDuringAnExclusiveOperation() {
		assertThrows(IllegalStateException.class,
				() -> activity.duringExclusiveOperation(() -> {
					activity.changeLocations(() -> { });
					return 1;
				}));
	}
}
