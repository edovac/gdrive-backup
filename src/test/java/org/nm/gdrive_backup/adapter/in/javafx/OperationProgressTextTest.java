package org.nm.gdrive_backup.adapter.in.javafx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.BackupPhase;
import org.nm.gdrive_backup.domain.model.BackupProgress;

class OperationProgressTextTest {

	private static final Instant START = Instant.parse("2026-09-20T10:00:00Z");
	private static final Instant NOW = START.plusSeconds(125);

	@Test
	void describesEachPhase() {
		assertEquals("Enumerating My Drive...", OperationProgressText.operation(
				progress(BackupPhase.ENUMERATING, null, 0, null, 1)));
		assertEquals("Finishing...", OperationProgressText.operation(
				progress(BackupPhase.FINISHED, null, 0, null, 1)));
	}

	@Test
	void countsKnownAndUnknownTotals() {
		assertEquals("Backing up 3 of 10 — a.txt", OperationProgressText.operation(
				progress(BackupPhase.BACKING_UP, "a.txt", 3, 10, 1)));
		assertEquals("4 changes processed", OperationProgressText.operation(
				progress(BackupPhase.BACKING_UP, null, 4, null, 1)));
	}

	@Test
	void namesTheDrivePositionInAMultiDriveJob() {
		assertEquals("My Drive — drive 2 of 3, 1 completed.", OperationProgressText.multiDrive(
				progress(BackupPhase.BACKING_UP, null, 0, null, 3)));
	}

	@Test
	void showsDriveTimeAndAddsJobTimeForMultiDriveJobs() {
		assertEquals("this drive: 2m 5s elapsed", OperationProgressText.time(
				progress(BackupPhase.BACKING_UP, null, 0, null, 1), NOW));
		assertEquals("this drive: 2m 5s elapsed — whole job: 2m 5s elapsed", OperationProgressText.time(
				progress(BackupPhase.BACKING_UP, null, 0, null, 3), NOW));
	}

	@Test
	void showsTheRemainingEstimateWhenKnown() {
		BackupProgress progress = new BackupProgress("My Drive", false, 1, 1, 0, BackupPhase.BACKING_UP, null, 1, 2,
				START, START, Duration.ofSeconds(59), null);

		assertEquals("this drive: 2m 5s elapsed, ~59s left", OperationProgressText.time(progress, NOW));
	}

	private static BackupProgress progress(BackupPhase phase, String item, int processed, Integer total,
			int totalDrives) {
		return new BackupProgress("My Drive", false, totalDrives > 1 ? 2 : 1, totalDrives, totalDrives > 1 ? 1 : 0,
				phase, item, processed, total, START, START, null, null);
	}
}
