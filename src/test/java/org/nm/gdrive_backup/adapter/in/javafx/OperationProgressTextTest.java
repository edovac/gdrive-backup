package org.nm.gdrive_backup.adapter.in.javafx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.BackupPhase;
import org.nm.gdrive_backup.domain.model.BackupProgress;
import org.nm.gdrive_backup.domain.model.FileDownload;

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

	@Test
	void downloadRowsShowOnlyTheFileNameWithTheFullPathAsTheHoverText() {
		BackupProgress progress = withDownloads(
				new FileDownload("id-1", "Q3.pdf", "My Drive/Reports/2026/Q3.pdf", START, null));

		List<OperationProgressText.DownloadRow> rows = OperationProgressText.downloadRows(progress, NOW);

		assertEquals(List.of(new OperationProgressText.DownloadRow("id-1", "Q3.pdf", "My Drive/Reports/2026/Q3.pdf", false)),
				rows);
	}

	@Test
	void downloadRowsKeepFilesInFlightAndRecentlyFinishedOnesOnly() {
		Instant justNow = NOW.minusSeconds(1);
		Instant longAgo = NOW.minus(OperationProgressText.FINISHED_VISIBLE_FOR).minusMillis(1);
		BackupProgress progress = withDownloads(
				new FileDownload("id-1", "Running.pdf", "My Drive/Running.pdf", START, null),
				new FileDownload("id-2", "Done.pdf", "My Drive/Done.pdf", START, justNow),
				new FileDownload("id-3", "Old.pdf", "My Drive/Old.pdf", START, longAgo));

		List<OperationProgressText.DownloadRow> rows = OperationProgressText.downloadRows(progress, NOW);

		assertEquals(List.of("Running.pdf", "Done.pdf"), rows.stream().map(OperationProgressText.DownloadRow::text).toList());
		assertEquals(List.of(false, true), rows.stream().map(OperationProgressText.DownloadRow::finished).toList());
	}

	@Test
	void aFinishedRowDisappearsExactlyWhenItsVisibleTimeRunsOut() {
		Instant finishedAt = NOW.minus(OperationProgressText.FINISHED_VISIBLE_FOR).plusMillis(1);
		BackupProgress progress = withDownloads(new FileDownload("id-1", "A.pdf", "My Drive/A.pdf", START, finishedAt));

		assertEquals(1, OperationProgressText.downloadRows(progress, NOW).size());
		assertEquals(0, OperationProgressText.downloadRows(progress, NOW.plusMillis(1)).size());
	}

	@Test
	void downloadRowsFallBackToTheIdAndNameWhenNameOrPathIsMissing() {
		BackupProgress progress = withDownloads(
				new FileDownload("id-1", " ", "My Drive/x", START, null),
				new FileDownload("id-2", "B.pdf", null, START, null));

		List<OperationProgressText.DownloadRow> rows = OperationProgressText.downloadRows(progress, NOW);

		assertEquals("id-1", rows.get(0).text());
		assertEquals("B.pdf", rows.get(1).text());
		assertEquals("B.pdf", rows.get(1).tooltip());
	}

	@Test
	void noDownloadsGiveNoRows() {
		assertEquals(List.of(), OperationProgressText.downloadRows(
				progress(BackupPhase.BACKING_UP, null, 0, null, 1), NOW));
	}

	private static BackupProgress withDownloads(FileDownload... downloads) {
		return new BackupProgress("My Drive", false, 1, 1, 0, BackupPhase.BACKING_UP, null, 0, null, START, START, null,
				null, List.of(downloads));
	}

	private static BackupProgress progress(BackupPhase phase, String item, int processed, Integer total,
			int totalDrives) {
		return new BackupProgress("My Drive", false, totalDrives > 1 ? 2 : 1, totalDrives, totalDrives > 1 ? 1 : 0,
				phase, item, processed, total, START, START, null, null);
	}
}
