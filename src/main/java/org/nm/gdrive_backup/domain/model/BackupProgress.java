package org.nm.gdrive_backup.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A snapshot of a backup job's progress. {@code totalItems}, {@code driveRemaining}
 * and {@code jobRemaining} are null when they cannot yet be estimated — for example
 * during an incremental sync, whose change total is unknown until the run ends.
 * {@code downloads} lists the files being downloaded (in start order) followed by the ones that
 * finished most recently (newest first); it is empty outside the download phase.
 */
public record BackupProgress(
		String driveName,
		boolean sharedDrive,
		int driveNumber,
		int totalDrives,
		int completedDrives,
		BackupPhase phase,
		String currentItem,
		int processedItems,
		Integer totalItems,
		Instant jobStartedAt,
		Instant driveStartedAt,
		Duration driveRemaining,
		Duration jobRemaining,
		List<FileDownload> downloads) {

	public BackupProgress {
		downloads = downloads == null ? List.of() : List.copyOf(downloads);
	}

	/** A snapshot with no per-file downloads to show. */
	public BackupProgress(String driveName, boolean sharedDrive, int driveNumber, int totalDrives,
			int completedDrives, BackupPhase phase, String currentItem, int processedItems, Integer totalItems,
			Instant jobStartedAt, Instant driveStartedAt, Duration driveRemaining, Duration jobRemaining) {
		this(driveName, sharedDrive, driveNumber, totalDrives, completedDrives, phase, currentItem, processedItems,
				totalItems, jobStartedAt, driveStartedAt, driveRemaining, jobRemaining, List.of());
	}
}
