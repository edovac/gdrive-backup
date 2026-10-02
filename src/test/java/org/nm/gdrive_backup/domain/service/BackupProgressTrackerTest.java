package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.BackupPhase;
import org.nm.gdrive_backup.domain.model.BackupProgress;
import org.nm.gdrive_backup.domain.model.FileDownload;
import org.nm.gdrive_backup.domain.port.out.BackupProgressPort;

class BackupProgressTrackerTest {

	private static final AvailableDrive PERSONAL = new AvailableDrive("root", "My Drive", false);
	private static final AvailableDrive SHARED = new AvailableDrive("drive-1", "Finance", true);
	private static final AvailableDrive THIRD = new AvailableDrive("drive-2", "Marketing", true);

	private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
	private final BackupProgressPort port = mock(BackupProgressPort.class);
	private final BackupProgressTracker tracker = new BackupProgressTracker(port, clock);

	@Test
	void tracksDriveNumberingAndCompletedCountAcrossATwoDriveJob() {
		tracker.jobStarted(List.of(PERSONAL, SHARED));
		tracker.driveStarted(PERSONAL);
		tracker.driveCompleted();
		tracker.driveStarted(SHARED);
		tracker.driveCompleted();
		tracker.jobFinished();

		ArgumentCaptor<BackupProgress> captor = ArgumentCaptor.forClass(BackupProgress.class);
		verify(port, atLeastOnce()).report(captor.capture());
		List<BackupProgress> snapshots = captor.getAllValues();

		BackupProgress afterFirstDriveStarted = snapshots.get(1);
		assertEquals("My Drive", afterFirstDriveStarted.driveName());
		assertEquals(1, afterFirstDriveStarted.driveNumber());
		assertEquals(2, afterFirstDriveStarted.totalDrives());
		assertEquals(0, afterFirstDriveStarted.completedDrives());

		BackupProgress afterSecondDriveStarted = snapshots.get(3);
		assertEquals("Shared: Finance", afterSecondDriveStarted.driveName());
		assertEquals(2, afterSecondDriveStarted.driveNumber());
		assertEquals(1, afterSecondDriveStarted.completedDrives());

		BackupProgress last = snapshots.get(snapshots.size() - 1);
		assertEquals(BackupPhase.FINISHED, last.phase());
		assertEquals(2, last.completedDrives());
	}

	@Test
	void aFailedDriveStillCountsAsCompleted() {
		tracker.jobStarted(List.of(PERSONAL, SHARED));
		tracker.driveStarted(PERSONAL);
		tracker.driveFailed();
		tracker.driveStarted(SHARED);

		ArgumentCaptor<BackupProgress> captor = ArgumentCaptor.forClass(BackupProgress.class);
		verify(port, atLeastOnce()).report(captor.capture());
		BackupProgress last = captor.getAllValues().get(captor.getAllValues().size() - 1);
		assertEquals(2, last.driveNumber());
		assertEquals(1, last.completedDrives());
	}

	@Test
	void reportsPackagingPhase() {
		tracker.jobStarted(List.of(PERSONAL));
		tracker.driveStarted(PERSONAL);

		ArgumentCaptor<BackupProgress> captor = ArgumentCaptor.forClass(BackupProgress.class);
		tracker.packaging();
		verify(port, atLeastOnce()).report(captor.capture());

		assertEquals(BackupPhase.PACKAGING, captor.getValue().phase());
	}

	@Test
	void computesDeterministicEtaOnceItemsAreProcessed() {
		tracker.jobStarted(List.of(PERSONAL));
		tracker.driveStarted(PERSONAL);
		tracker.enumerated(10);
		clock.advance(Duration.ofSeconds(10));

		ArgumentCaptor<BackupProgress> captor = ArgumentCaptor.forClass(BackupProgress.class);
		tracker.itemProcessed("file-1");
		verify(port, atLeastOnce()).report(captor.capture());

		BackupProgress snapshot = captor.getValue();
		// 1 of 10 processed in 10s elapsed => 9 remaining * 10s/item = 90s.
		assertEquals(Duration.ofSeconds(90), snapshot.driveRemaining());
		// Single-drive job: job remaining coincides with drive remaining.
		assertEquals(Duration.ofSeconds(90), snapshot.jobRemaining());
	}

	@Test
	void hasNoEtaWhenTheTotalIsUnknownOrNothingHasBeenProcessedYet() {
		tracker.jobStarted(List.of(PERSONAL));
		tracker.driveStarted(PERSONAL);

		ArgumentCaptor<BackupProgress> captor = ArgumentCaptor.forClass(BackupProgress.class);
		tracker.itemProcessed("change-1");
		verify(port, atLeastOnce()).report(captor.capture());

		BackupProgress snapshot = captor.getValue();
		assertNull(snapshot.totalItems());
		assertNull(snapshot.driveRemaining());
		assertNull(snapshot.jobRemaining());
	}

	@Test
	void estimatesJobRemainingFromTheAverageOfCompletedDrivesOnceOneHasFinished() {
		tracker.jobStarted(List.of(PERSONAL, SHARED, THIRD));
		tracker.driveStarted(PERSONAL);
		tracker.enumerated(1);
		clock.advance(Duration.ofSeconds(100));
		tracker.itemProcessed("only-file");
		tracker.driveCompleted();

		tracker.driveStarted(SHARED);
		tracker.enumerated(10);
		clock.advance(Duration.ofSeconds(10));

		ArgumentCaptor<BackupProgress> captor = ArgumentCaptor.forClass(BackupProgress.class);
		tracker.itemProcessed("shared-file-1");
		verify(port, atLeastOnce()).report(captor.capture());

		BackupProgress snapshot = captor.getValue();
		// Second drive: 1 of 10 in 10s elapsed => 9 remaining * 10s/item = 90s.
		assertEquals(Duration.ofSeconds(90), snapshot.driveRemaining());
		// One drive left unstarted (THIRD); its estimate falls back to the one completed
		// drive's duration (100s): 90s + 100s * 1 = 190s.
		assertEquals(Duration.ofSeconds(190), snapshot.jobRemaining());
	}

	@Test
	void countsEveryItemWhenSeveralThreadsReportAtOnce() throws Exception {
		int threads = 8;
		int perThread = 500;
		tracker.jobStarted(List.of(PERSONAL));
		tracker.driveStarted(PERSONAL);
		tracker.enumerated(threads * perThread);
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<?>> done = new ArrayList<>();
			for (int thread = 0; thread < threads; thread++) {
				done.add(executor.submit(() -> {
					start.await();
					for (int i = 0; i < perThread; i++) {
						tracker.itemProcessed("file");
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> future : done) {
				future.get(10, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
		}

		ArgumentCaptor<BackupProgress> captor = ArgumentCaptor.forClass(BackupProgress.class);
		verify(port, atLeastOnce()).report(captor.capture());
		List<BackupProgress> snapshots = captor.getAllValues();
		assertEquals(threads * perThread, snapshots.get(snapshots.size() - 1).processedItems());
		// Each report happens under the tracker's lock, so the counts a port sees never go backwards.
		for (int i = 1; i < snapshots.size(); i++) {
			assertTrue(snapshots.get(i).processedItems() >= snapshots.get(i - 1).processedItems());
		}
	}

	@Test
	void listsFilesInFlightInStartOrderWithTheirNameAndPath() {
		tracker.jobStarted(List.of(PERSONAL));
		tracker.driveStarted(PERSONAL);
		tracker.downloadStarted("id-1", "A.pdf", "My Drive/Docs/A.pdf", null);
		clock.advance(Duration.ofSeconds(1));
		tracker.downloadStarted("id-2", "B.pdf", "My Drive/B.pdf", null);

		List<FileDownload> downloads = lastSnapshot().downloads();

		assertEquals(List.of("id-1", "id-2"), downloads.stream().map(FileDownload::fileId).toList());
		assertEquals("A.pdf", downloads.get(0).name());
		assertEquals("My Drive/Docs/A.pdf", downloads.get(0).path());
		assertTrue(downloads.stream().noneMatch(FileDownload::finished));
	}

	@Test
	void carriesTheBytesDownloadedSoFarAndTheTotalWhenDriveReportedOne() {
		tracker.jobStarted(List.of(PERSONAL));
		tracker.driveStarted(PERSONAL);
		tracker.downloadStarted("id-1", "Big.pdf", "My Drive/Big.pdf", 1_000L);
		tracker.downloadStarted("id-2", "Doc", "My Drive/Doc", null);

		assertEquals(0, lastSnapshot().downloads().get(0).bytesDownloaded());
		assertEquals(1_000L, lastSnapshot().downloads().get(0).totalBytes());
		assertNull(lastSnapshot().downloads().get(1).totalBytes());

		tracker.downloadProgressed("id-1", 250);
		tracker.downloadProgressed("id-2", 40);

		List<FileDownload> downloads = lastSnapshot().downloads();
		assertEquals(250, downloads.get(0).bytesDownloaded());
		assertEquals(40, downloads.get(1).bytesDownloaded());
		assertEquals(1_000L, downloads.get(0).totalBytes());
	}

	@Test
	void progressKeepsTheStartOrderOfTheDownloads() {
		tracker.jobStarted(List.of(PERSONAL));
		tracker.driveStarted(PERSONAL);
		tracker.downloadStarted("id-1", "A", "My Drive/A", null);
		tracker.downloadStarted("id-2", "B", "My Drive/B", null);
		tracker.downloadStarted("id-3", "C", "My Drive/C", null);

		tracker.downloadProgressed("id-2", 10);
		tracker.downloadProgressed("id-1", 20);

		assertEquals(List.of("id-1", "id-2", "id-3"),
				lastSnapshot().downloads().stream().map(FileDownload::fileId).toList());
	}

	@Test
	void progressForAnUnknownOrAlreadyFinishedDownloadIsIgnored() {
		tracker.jobStarted(List.of(PERSONAL));
		tracker.driveStarted(PERSONAL);
		tracker.downloadStarted("id-1", "A", "My Drive/A", null);
		tracker.downloadProgressed("id-1", 100);
		tracker.downloadFinished("id-1");

		tracker.downloadProgressed("id-1", 999);
		tracker.downloadProgressed("unknown", 5);

		List<FileDownload> downloads = lastSnapshot().downloads();
		assertEquals(1, downloads.size());
		assertEquals(100, downloads.get(0).bytesDownloaded());
	}

	@Test
	void repeatingTheSameByteCountDoesNotReportAgain() {
		tracker.jobStarted(List.of(PERSONAL));
		tracker.driveStarted(PERSONAL);
		tracker.downloadStarted("id-1", "A", "My Drive/A", null);
		tracker.downloadProgressed("id-1", 100);
		ArgumentCaptor<BackupProgress> captor = ArgumentCaptor.forClass(BackupProgress.class);
		verify(port, atLeastOnce()).report(captor.capture());
		int reports = captor.getAllValues().size();

		tracker.downloadProgressed("id-1", 100);

		ArgumentCaptor<BackupProgress> after = ArgumentCaptor.forClass(BackupProgress.class);
		verify(port, atLeastOnce()).report(after.capture());
		assertEquals(reports, after.getAllValues().size());
	}

	@Test
	void aFinishedDownloadKeepsItsFinalSizeAndTotal() {
		tracker.jobStarted(List.of(PERSONAL));
		tracker.driveStarted(PERSONAL);
		tracker.downloadStarted("id-1", "A", "My Drive/A", 500L);
		tracker.downloadProgressed("id-1", 500);

		tracker.downloadFinished("id-1");

		FileDownload finished = lastSnapshot().downloads().get(0);
		assertTrue(finished.finished());
		assertEquals(500, finished.bytesDownloaded());
		assertEquals(500L, finished.totalBytes());
	}

	@Test
	void countsBytesCorrectlyWhenSeveralThreadsReportProgressAtOnce() throws Exception {
		int threads = 8;
		tracker.jobStarted(List.of(PERSONAL));
		tracker.driveStarted(PERSONAL);
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<?>> done = new ArrayList<>();
			for (int thread = 0; thread < threads; thread++) {
				String id = "id-" + thread;
				tracker.downloadStarted(id, id, "My Drive/" + id, null);
				done.add(executor.submit(() -> {
					start.await();
					for (long bytes = 1; bytes <= 500; bytes++) {
						tracker.downloadProgressed(id, bytes);
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> future : done) {
				future.get(10, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
		}

		List<FileDownload> downloads = lastSnapshot().downloads();
		assertEquals(threads, downloads.size());
		assertTrue(downloads.stream().allMatch(download -> download.bytesDownloaded() == 500));
	}

	@Test
	void aFinishedDownloadMovesAfterTheOnesStillRunningAndKeepsItsFinishTime() {
		tracker.jobStarted(List.of(PERSONAL));
		tracker.driveStarted(PERSONAL);
		tracker.downloadStarted("id-1", "A.pdf", "My Drive/A.pdf", null);
		tracker.downloadStarted("id-2", "B.pdf", "My Drive/B.pdf", null);
		clock.advance(Duration.ofSeconds(5));

		tracker.downloadFinished("id-1");

		List<FileDownload> downloads = lastSnapshot().downloads();
		assertEquals(List.of("id-2", "id-1"), downloads.stream().map(FileDownload::fileId).toList());
		assertEquals(false, downloads.get(0).finished());
		assertEquals(true, downloads.get(1).finished());
		assertEquals(Instant.parse("2026-01-01T00:00:05Z"), downloads.get(1).finishedAt());
	}

	@Test
	void recentlyFinishedDownloadsAreListedNewestFirstAndBounded() {
		tracker.jobStarted(List.of(PERSONAL));
		tracker.driveStarted(PERSONAL);
		int count = BackupProgressTracker.MAX_FINISHED_DOWNLOADS + 4;
		for (int i = 0; i < count; i++) {
			tracker.downloadStarted("id-" + i, "F" + i, "My Drive/F" + i, null);
			tracker.downloadFinished("id-" + i);
		}

		List<FileDownload> downloads = lastSnapshot().downloads();

		assertEquals(BackupProgressTracker.MAX_FINISHED_DOWNLOADS, downloads.size());
		assertEquals("id-" + (count - 1), downloads.get(0).fileId());
		assertEquals("id-" + (count - BackupProgressTracker.MAX_FINISHED_DOWNLOADS),
				downloads.get(downloads.size() - 1).fileId());
	}

	@Test
	void countsEveryFinishedDownloadEvenThoughOnlyTheLastFewAreListed() {
		tracker.jobStarted(List.of(PERSONAL));
		tracker.driveStarted(PERSONAL);
		int count = BackupProgressTracker.MAX_FINISHED_DOWNLOADS * 3 + 5;
		for (int i = 0; i < count; i++) {
			tracker.downloadStarted("id-" + i, "F" + i, "My Drive/F" + i, null);
			tracker.downloadFinished("id-" + i);
		}
		tracker.downloadStarted("running", "Running", "My Drive/Running", null);

		BackupProgress snapshot = lastSnapshot();

		assertEquals(count, snapshot.finishedDownloads());
		assertEquals(BackupProgressTracker.MAX_FINISHED_DOWNLOADS + 1, snapshot.downloads().size());
		assertEquals(20, BackupProgressTracker.MAX_FINISHED_DOWNLOADS);
	}

	@Test
	void anAbortedDownloadIsNotCounted() {
		tracker.jobStarted(List.of(PERSONAL));
		tracker.driveStarted(PERSONAL);
		tracker.downloadStarted("id-1", "A", "My Drive/A", null);
		tracker.downloadStarted("id-2", "B", "My Drive/B", null);

		tracker.downloadAborted("id-1");
		tracker.downloadFinished("id-2");
		tracker.downloadFinished("never-started");

		assertEquals(1, lastSnapshot().finishedDownloads());
	}

	@Test
	void theFinishedCountStartsOverWithEachDrive() {
		tracker.jobStarted(List.of(PERSONAL, SHARED));
		tracker.driveStarted(PERSONAL);
		tracker.downloadStarted("id-1", "A", "My Drive/A", null);
		tracker.downloadFinished("id-1");
		assertEquals(1, lastSnapshot().finishedDownloads());
		tracker.driveCompleted();

		tracker.driveStarted(SHARED);

		assertEquals(0, lastSnapshot().finishedDownloads());
	}

	@Test
	void theByteTotalAddsFinishedDownloadsToWhatTheRunningOnesHaveReceived() {
		tracker.jobStarted(List.of(PERSONAL, SHARED));
		tracker.driveStarted(PERSONAL);
		tracker.downloadStarted("id-1", "A", "My Drive/A", 1000L);
		tracker.downloadStarted("id-2", "B", "My Drive/B", null);
		tracker.downloadStarted("id-3", "C", "My Drive/C", null);
		tracker.downloadProgressed("id-1", 1000);
		tracker.downloadFinished("id-1");
		tracker.downloadProgressed("id-2", 300);
		tracker.downloadProgressed("id-3", 50);
		tracker.downloadAborted("id-3");

		assertEquals(1300, lastSnapshot().downloadedBytes(), "an aborted download no longer counts");

		tracker.driveCompleted();
		tracker.driveStarted(SHARED);

		assertEquals(0, lastSnapshot().downloadedBytes());
	}

	@Test
	void aSnapshotBuiltWithoutAnExplicitCountDerivesItFromTheFinishedDownloads() {
		Instant now = Instant.parse("2026-01-01T00:00:00Z");
		List<FileDownload> downloads = List.of(
				new FileDownload("a", "A", "My Drive/A", now, null),
				new FileDownload("b", "B", "My Drive/B", now, now),
				new FileDownload("c", "C", "My Drive/C", now, now));

		BackupProgress progress = new BackupProgress("My Drive", false, 1, 1, 0, BackupPhase.BACKING_UP, null, 0, null,
				now, now, null, null, downloads);

		assertEquals(2, progress.finishedDownloads());
		assertEquals(0, new BackupProgress("My Drive", false, 1, 1, 0, BackupPhase.BACKING_UP, null, 0, null, now, now,
				null, null).finishedDownloads());
	}

	@Test
	void anAbortedDownloadLeavesTheListWithoutBeingShownAsDone() {
		tracker.jobStarted(List.of(PERSONAL));
		tracker.driveStarted(PERSONAL);
		tracker.downloadStarted("id-1", "A.pdf", "My Drive/A.pdf", null);

		tracker.downloadAborted("id-1");

		assertTrue(lastSnapshot().downloads().isEmpty());
	}

	@Test
	void finishingOrAbortingADownloadThatWasNeverStartedChangesNothing() {
		tracker.jobStarted(List.of(PERSONAL));
		tracker.driveStarted(PERSONAL);
		tracker.downloadFinished("unknown");
		tracker.downloadAborted("unknown");

		assertTrue(lastSnapshot().downloads().isEmpty());
	}

	@Test
	void startingTheNextDriveClearsTheDownloadList() {
		tracker.jobStarted(List.of(PERSONAL, SHARED));
		tracker.driveStarted(PERSONAL);
		tracker.downloadStarted("id-1", "A.pdf", "My Drive/A.pdf", null);
		tracker.downloadStarted("id-2", "B.pdf", "My Drive/B.pdf", null);
		tracker.downloadFinished("id-2");
		tracker.driveCompleted();

		tracker.driveStarted(SHARED);

		assertTrue(lastSnapshot().downloads().isEmpty());
	}

	@Test
	void aSnapshotsDownloadListIsNotAffectedByLaterEvents() {
		tracker.jobStarted(List.of(PERSONAL));
		tracker.driveStarted(PERSONAL);
		tracker.downloadStarted("id-1", "A.pdf", "My Drive/A.pdf", null);
		List<FileDownload> before = lastSnapshot().downloads();

		tracker.downloadFinished("id-1");

		assertEquals(1, before.size());
		assertEquals(false, before.get(0).finished());
	}

	@Test
	void trackersWithNoDownloadsReportAnEmptyList() {
		tracker.jobStarted(List.of(PERSONAL));

		assertTrue(lastSnapshot().downloads().isEmpty());
	}

	@Test
	void keepsTheDownloadListConsistentWhenSeveralThreadsStartAndFinishAtOnce() throws Exception {
		int threads = 8;
		int perThread = 200;
		tracker.jobStarted(List.of(PERSONAL));
		tracker.driveStarted(PERSONAL);
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<?>> done = new ArrayList<>();
			for (int thread = 0; thread < threads; thread++) {
				int offset = thread * perThread;
				done.add(executor.submit(() -> {
					start.await();
					for (int i = 0; i < perThread; i++) {
						String id = "id-" + (offset + i);
						tracker.downloadStarted(id, id, "My Drive/" + id, null);
						tracker.downloadFinished(id);
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> future : done) {
				future.get(10, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
		}

		List<FileDownload> downloads = lastSnapshot().downloads();
		assertEquals(BackupProgressTracker.MAX_FINISHED_DOWNLOADS, downloads.size());
		assertTrue(downloads.stream().allMatch(FileDownload::finished), "nothing is left in flight");
		assertEquals(threads * perThread, lastSnapshot().finishedDownloads(), "no finish was lost");
	}

	private BackupProgress lastSnapshot() {
		ArgumentCaptor<BackupProgress> captor = ArgumentCaptor.forClass(BackupProgress.class);
		verify(port, atLeastOnce()).report(captor.capture());
		return captor.getValue();
	}

	private static final class MutableClock extends Clock {
		private Instant instant;

		MutableClock(Instant instant) {
			this.instant = instant;
		}

		void advance(Duration duration) {
			instant = instant.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
