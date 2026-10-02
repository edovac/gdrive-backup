package org.nm.gdrive_backup.adapter.in.javafx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.BackupResult;
import org.nm.gdrive_backup.domain.model.DownloadFailure;
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

	@Test
	void listsTheFilesASuccessfulDriveSkipped() {
		BackupResult result = new BackupResult(PERSONAL, 4, true, false, null, List.of(skipped("a"), skipped("b")));

		String text = BackupSummaryText.summary(List.of(result), SELECTION, false);

		assertEquals("Synchronization complete, but 2 file(s) could not be backed up.\n"
				+ "My Drive (user@example.com): 4 files inventoried"
				+ "\n    Not backed up: My Drive/a.pdf — forbidden"
				+ "\n    Not backed up: My Drive/b.pdf — forbidden"
				+ "\nShared: Finance: not started", text);
	}

	@Test
	void namesOnlyTheFirstSkippedFilesAndPointsAtTheReportForTheRest() {
		List<DownloadFailure> many = new java.util.ArrayList<>();
		for (int i = 0; i < FailedFilesText.SUMMARY_LIMIT + 3; i++) {
			many.add(skipped("f" + i));
		}

		String text = BackupSummaryText.summary(
				List.of(new BackupResult(PERSONAL, 4, true, false, null, many), new BackupResult(SHARED, 1, false, false)),
				SELECTION, false);

		assertTrue(text.contains("My Drive/f9.pdf"));
		assertFalse(text.contains("My Drive/f10.pdf"));
		assertTrue(text.contains("… and 3 more (see the Failed files tab)"));
	}

	@Test
	void theHeadlineCountsSkippedFilesNextToFailedDrives() {
		List<BackupResult> results = List.of(
				new BackupResult(PERSONAL, 4, true, false, null, List.of(skipped("a"))),
				BackupResult.failed(SHARED, "No access"));

		assertEquals("Synchronization complete with 1 failed drive(s) and 1 file(s) could not be backed up.",
				BackupSummaryText.headline(results, false));
		assertEquals("Synchronization cancelled. 1 drive(s) failed. 1 file(s) could not be backed up.",
				BackupSummaryText.headline(results, true));
	}

	private static DownloadFailure skipped(String fileId) {
		return DownloadFailure.found(PERSONAL.key(), fileId, fileId + ".pdf", "My Drive/" + fileId + ".pdf", "forbidden",
				java.time.Instant.parse("2026-09-20T10:00:00Z"));
	}

	@Test
	void theHeadlineAloneCoversSuccessFailureAndCancellation() {
		assertEquals("Synchronization complete.",
				BackupSummaryText.headline(List.of(new BackupResult(PERSONAL, 4, true, false)), false));
		assertEquals("Synchronization complete with 1 failed drive(s).",
				BackupSummaryText.headline(List.of(BackupResult.failed(SHARED, "No access")), false));
		assertEquals("Synchronization cancelled.",
				BackupSummaryText.headline(List.of(new BackupResult(PERSONAL, 4, true, true)), true));
		assertEquals("Synchronization cancelled. 1 drive(s) failed.",
				BackupSummaryText.headline(List.of(BackupResult.failed(PERSONAL, "Boom")), true));
	}
}
