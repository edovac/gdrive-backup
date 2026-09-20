package org.nm.gdrive_backup.domain.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.BackupPhase;
import org.nm.gdrive_backup.domain.model.BackupProgress;
import org.nm.gdrive_backup.domain.port.out.BackupProgressPort;

/**
 * Folds sync events from the backup services into {@link BackupProgress} snapshots,
 * adding elapsed and estimated-remaining time. One tracker is shared across an entire
 * multi-drive job; {@link #jobStarted} resets it, so a later job always starts clean.
 */
public class BackupProgressTracker {

	/** A tracker with no port to report to; used where no progress observer is wired. */
	public static final BackupProgressTracker NO_OP = new BackupProgressTracker(null, Clock.systemUTC());

	private final BackupProgressPort port;
	private final Clock clock;
	private final List<Duration> completedDriveDurations = new ArrayList<>();

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

	public void jobStarted(List<AvailableDrive> drives) {
		totalDrives = drives.size();
		driveIndex = -1;
		completedDrives = 0;
		completedDriveDurations.clear();
		jobStartedAt = clock.instant();
		phase = BackupPhase.ENUMERATING;
		report();
	}

	public void driveStarted(AvailableDrive drive) {
		driveIndex++;
		driveName = (drive.shared() ? "Shared: " : "") + drive.name();
		sharedDrive = drive.shared();
		driveStartedAt = clock.instant();
		phase = BackupPhase.ENUMERATING;
		processedItems = 0;
		totalItems = null;
		currentItem = null;
		report();
	}

	public void enumerating() {
		phase = BackupPhase.ENUMERATING;
		report();
	}

	public void enumerated(int total) {
		totalItems = total;
		phase = BackupPhase.BACKING_UP;
		report();
	}

	public void itemProcessed(String itemName) {
		processedItems++;
		currentItem = itemName;
		phase = BackupPhase.BACKING_UP;
		report();
	}

	public void packaging() {
		phase = BackupPhase.PACKAGING;
		report();
	}

	public void driveCompleted() {
		completedDriveDurations.add(Duration.between(driveStartedAt, clock.instant()));
		completedDrives++;
		report();
	}

	/** A drive that threw still counts as done for the job's progress and time estimate. */
	public void driveFailed() {
		driveCompleted();
	}

	public void jobFinished() {
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
		port.report(new BackupProgress(driveName, sharedDrive, driveIndex + 1, totalDrives, completedDrives, phase,
				currentItem, processedItems, totalItems, jobStartedAt, driveStartedAt, driveRemaining, jobRemaining));
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
