package org.nm.gdrive_backup.domain.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.BackupPhase;
import org.nm.gdrive_backup.domain.model.BackupProgress;
import org.nm.gdrive_backup.domain.model.FileDownload;
import org.nm.gdrive_backup.domain.port.out.BackupProgressPort;

/**
 * Folds sync events from the backup services into {@link BackupProgress} snapshots,
 * adding elapsed and estimated-remaining time. One tracker is shared across an entire
 * multi-drive job; {@link #jobStarted} resets it, so a later job always starts clean.
 */
public class BackupProgressTracker {

	/** A tracker with no port to report to; used where no progress observer is wired. */
	public static final BackupProgressTracker NO_OP = new BackupProgressTracker(null, Clock.systemUTC());

	/** How many of the most recently finished downloads a snapshot carries; the count of all of them is separate. */
	static final int MAX_FINISHED_DOWNLOADS = 20;

	private final BackupProgressPort port;
	private final Clock clock;
	private final List<Duration> completedDriveDurations = new ArrayList<>();
	/** Downloads in flight, in start order, and the last few that finished, newest first. */
	private final Map<String, FileDownload> downloading = new LinkedHashMap<>();
	private final Deque<FileDownload> finished = new ArrayDeque<>();
	private int finishedDownloads;
	/** Bytes of every download finished in the current drive; the running ones are added when reporting. */
	private long finishedBytes;

	private int totalDrives;
	private int driveIndex = -1;
	private int completedDrives;
	private String driveName;
	private boolean sharedDrive;
	private BackupPhase phase;
	private int processedItems;
	private Integer totalItems;
	private String currentItem;
	private Instant jobStartedAt;
	private Instant driveStartedAt;

	public BackupProgressTracker(BackupProgressPort port, Clock clock) {
		this.port = port;
		this.clock = clock;
	}

	public synchronized void jobStarted(List<AvailableDrive> drives) {
		totalDrives = drives.size();
		driveIndex = -1;
		completedDrives = 0;
		completedDriveDurations.clear();
		jobStartedAt = clock.instant();
		phase = BackupPhase.ENUMERATING;
		report();
	}

	public synchronized void driveStarted(AvailableDrive drive) {
		driveIndex++;
		driveName = (drive.shared() ? "Shared: " : "") + drive.name();
		sharedDrive = drive.shared();
		driveStartedAt = clock.instant();
		phase = BackupPhase.ENUMERATING;
		processedItems = 0;
		totalItems = null;
		currentItem = null;
		downloading.clear();
		finished.clear();
		finishedDownloads = 0;
		finishedBytes = 0;
		report();
	}

	public synchronized void enumerating() {
		phase = BackupPhase.ENUMERATING;
		report();
	}

	public synchronized void enumerated(int total) {
		totalItems = total;
		phase = BackupPhase.BACKING_UP;
		report();
	}

	public synchronized void itemProcessed(String itemName) {
		processedItems++;
		currentItem = itemName;
		phase = BackupPhase.BACKING_UP;
		report();
	}

	/** A file's download began; it stays in the snapshot's download list until it finishes or is aborted. */
	public synchronized void downloadStarted(String fileId, String name, String path, Long totalBytesOrNull) {
		downloading.put(fileId, new FileDownload(fileId, name, path, clock.instant(), null, 0, totalBytesOrNull));
		report();
	}

	/** How many bytes of the file have arrived so far; the entry keeps its place in the start order. */
	public synchronized void downloadProgressed(String fileId, long bytesDownloaded) {
		FileDownload download = downloading.get(fileId);
		if (download == null || download.bytesDownloaded() == bytesDownloaded) {
			return;
		}
		downloading.put(fileId, download.withBytesDownloaded(bytesDownloaded));
		report();
	}

	/** The download completed; it moves to the short list of recently finished files. */
	public synchronized void downloadFinished(String fileId) {
		FileDownload started = downloading.remove(fileId);
		if (started == null) {
			return;
		}
		finishedDownloads++;
		finishedBytes += started.bytesDownloaded();
		finished.addFirst(started.withFinishedAt(clock.instant()));
		while (finished.size() > MAX_FINISHED_DOWNLOADS) {
			finished.removeLast();
		}
		report();
	}

	/** The download failed or was cancelled, so it leaves the list without being shown as done. */
	public synchronized void downloadAborted(String fileId) {
		if (downloading.remove(fileId) != null) {
			report();
		}
	}

	public synchronized void packaging() {
		phase = BackupPhase.PACKAGING;
		report();
	}

	public synchronized void driveCompleted() {
		completedDriveDurations.add(Duration.between(driveStartedAt, clock.instant()));
		completedDrives++;
		report();
	}

	/** A drive that threw still counts as done for the job's progress and time estimate. */
	public synchronized void driveFailed() {
		driveCompleted();
	}

	public synchronized void jobFinished() {
		phase = BackupPhase.FINISHED;
		report();
	}

	private void report() {
		if (port == null) {
			return;
		}
		Instant now = clock.instant();
		Duration driveRemaining = driveRemaining(now);
		Duration jobRemaining = jobRemaining(now, driveRemaining);
		List<FileDownload> downloads = new ArrayList<>(downloading.values());
		long downloadedBytes = finishedBytes + downloads.stream().mapToLong(FileDownload::bytesDownloaded).sum();
		downloads.addAll(finished);
		port.report(new BackupProgress(driveName, sharedDrive, driveIndex + 1, totalDrives, completedDrives, phase,
				currentItem, processedItems, totalItems, jobStartedAt, driveStartedAt, driveRemaining, jobRemaining,
				downloads, finishedDownloads, downloadedBytes));
	}

	private Duration driveRemaining(Instant now) {
		if (totalItems == null || processedItems <= 0 || driveStartedAt == null) {
			return null;
		}
		int remainingItems = totalItems - processedItems;
		if (remainingItems <= 0) {
			return Duration.ZERO;
		}
		Duration elapsed = Duration.between(driveStartedAt, now);
		Duration perItem = elapsed.dividedBy(processedItems);
		return perItem.multipliedBy(remainingItems);
	}

	private Duration jobRemaining(Instant now, Duration driveRemaining) {
		if (driveRemaining == null) {
			return null;
		}
		int drivesNotStarted = totalDrives - (driveIndex + 1);
		Duration perDriveAverage = completedDriveDurations.isEmpty()
				? Duration.between(driveStartedAt, now).plus(driveRemaining)
				: averageOf(completedDriveDurations);
		return driveRemaining.plus(perDriveAverage.multipliedBy(drivesNotStarted));
	}

	private static Duration averageOf(List<Duration> durations) {
		Duration total = Duration.ZERO;
		for (Duration duration : durations) {
			total = total.plus(duration);
		}
		return total.dividedBy(durations.size());
	}
}
