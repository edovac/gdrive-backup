package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.BackupStopMode;

class BackupCancellationTest {

	@Test
	void startsNotRequested() {
		BackupCancellation cancellation = new BackupCancellation();

		assertFalse(cancellation.isStopRequested());
		assertFalse(cancellation.isImmediateStopRequested());
	}

	@Test
	void immediateRequestSetsBothFlags() {
		BackupCancellation cancellation = new BackupCancellation();

		cancellation.requestStop(BackupStopMode.IMMEDIATE);

		assertTrue(cancellation.isStopRequested());
		assertTrue(cancellation.isImmediateStopRequested());
	}

	@Test
	void afterCurrentDriveRequestOnlySetsTheGeneralFlag() {
		BackupCancellation cancellation = new BackupCancellation();

		cancellation.requestStop(BackupStopMode.AFTER_CURRENT_DRIVE);

		assertTrue(cancellation.isStopRequested());
		assertFalse(cancellation.isImmediateStopRequested());
	}

	@Test
	void beginClearsAPreviousRequestForTheNextJob() {
		BackupCancellation cancellation = new BackupCancellation();
		cancellation.requestStop(BackupStopMode.IMMEDIATE);

		cancellation.begin();

		assertFalse(cancellation.isStopRequested());
		assertFalse(cancellation.isImmediateStopRequested());
	}
}
