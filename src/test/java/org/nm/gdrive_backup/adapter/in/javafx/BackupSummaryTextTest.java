package org.nm.gdrive_backup.adapter.in.javafx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.BackupResult;
import org.nm.gdrive_backup.domain.model.DriveScope;

class BackupSummaryTextTest {

	private static final DriveScope PERSONAL = DriveScope.personal("user@example.com");
	private static final DriveScope SHARED = DriveScope.sharedDrive("drive-1");
	private static final List<AvailableDrive> SELECTION = List.of(
			new AvailableDrive("root", "My Drive", false),
			new AvailableDrive("drive-1", "Finance", true));

	@Test
	void summarisesASuccessfulRun() {
		String text = BackupSummaryText.summary(List.of(
				new BackupResult(PERSONAL, 4, true, false),
				new BackupResult(SHARED, 2, false, false)), SELECTION, false);

		assertEquals("Synchronization complete.\nMy Drive (user@example.com): 4 files inventoried"
				+ "\nShared: Finance: 2 changes processed", text);
	}

	@Test
	void namesTheFailedDriveAndItsReason() {
		String text = BackupSummaryText.summary(List.of(
				new BackupResult(PERSONAL, 4, true, false),
				BackupResult.failed(SHARED, "No access")), SELECTION, false);

		assertEquals("Synchronization complete with 1 failed drive(s).\nMy Drive (user@example.com): 4 files inventoried"
				+ "\nShared: Finance: FAILED — No access", text);
	}

	@Test
	void listsDrivesNeverReachedAfterACancel() {
		String text = BackupSummaryText.summary(List.of(new BackupResult(PERSONAL, 4, true, true)), SELECTION, true);

		assertEquals("Synchronization cancelled.\nMy Drive (user@example.com): 4 files inventoried (cancelled)"
				+ "\nShared: Finance: not started", text);
	}

	@Test
	void mentionsFailuresInACancelledRun() {
		String text = BackupSummaryText.summary(List.of(BackupResult.failed(PERSONAL, "Boom")), SELECTION, true);

		assertEquals("Synchronization cancelled. 1 drive(s) failed.\nMy Drive (user@example.com): FAILED — Boom"
				+ "\nShared: Finance: not started", text);
	}
}
