package org.nm.gdrive_backup.domain.model;

import java.time.Duration;
import java.time.Instant;

/**
 * A snapshot of a backup job's progress. {@code totalItems}, {@code driveRemaining}
 * and {@code jobRemaining} are null when they cannot yet be estimated — for example
 * during an incremental sync, whose change total is unknown until the run ends.
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
		Duration jobRemaining) {
}
