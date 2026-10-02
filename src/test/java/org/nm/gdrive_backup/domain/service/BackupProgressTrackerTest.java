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
