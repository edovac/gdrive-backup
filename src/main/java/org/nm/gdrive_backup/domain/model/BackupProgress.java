package org.nm.gdrive_backup.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A snapshot of a backup job's progress. {@code totalItems}, {@code driveRemaining}
 * and {@code jobRemaining} are null when they cannot yet be estimated — for example
 * during an incremental sync, whose change total is unknown until the run ends.
 * {@code downloads} lists the files being downloaded (in start order) followed by the most recently finished ones
 * (newest first), which is only a short history; {@code finishedDownloads} counts every file whose download has
 * finished in the current drive. {@code downloadedBytes} is how many bytes have arrived in the current drive so far:
 * every finished download plus what the running ones have received. All are empty or zero outside the download phase.
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
		List<FileDownload> downloads,
		int finishedDownloads,
		long downloadedBytes) {

	public BackupProgress {
		downloads = downloads == null ? List.of() : List.copyOf(downloads);
	}

	/** A snapshot whose finished count and byte total are derived from its download list. */
	public BackupProgress(String driveName, boolean sharedDrive, int driveNumber, int totalDrives,
			int completedDrives, BackupPhase phase, String currentItem, int processedItems, Integer totalItems,
			Instant jobStartedAt, Instant driveStartedAt, Duration driveRemaining, Duration jobRemaining,
			List<FileDownload> downloads) {
		this(driveName, sharedDrive, driveNumber, totalDrives, completedDrives, phase, currentItem, processedItems,
				totalItems, jobStartedAt, driveStartedAt, driveRemaining, jobRemaining, downloads,
				downloads == null ? 0 : (int) downloads.stream().filter(FileDownload::finished).count(),
				downloads == null ? 0 : downloads.stream().mapToLong(FileDownload::bytesDownloaded).sum());
	}

	/** A snapshot with no per-file downloads to show. */
	public BackupProgress(String driveName, boolean sharedDrive, int driveNumber, int totalDrives,
			int completedDrives, BackupPhase phase, String currentItem, int processedItems, Integer totalItems,
			Instant jobStartedAt, Instant driveStartedAt, Duration driveRemaining, Duration jobRemaining) {
		this(driveName, sharedDrive, driveNumber, totalDrives, completedDrives, phase, currentItem, processedItems,
				totalItems, jobStartedAt, driveStartedAt, driveRemaining, jobRemaining, List.of(), 0, 0);
	}
}
