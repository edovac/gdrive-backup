package org.nm.gdrive_backup.adapter.in.javafx;

import java.time.Duration;
import java.time.Instant;

import org.nm.gdrive_backup.domain.model.BackupPhase;
import org.nm.gdrive_backup.domain.model.BackupProgress;

/** Wording for a running operation's progress, kept free of JavaFX so it can be unit tested. */
final class OperationProgressText {

	private OperationProgressText() {
	}

	static String operation(BackupProgress progress) {
		String item = progress.currentItem() == null ? "" : " — " + progress.currentItem();
		if (progress.phase() == BackupPhase.ENUMERATING) {
			return "Enumerating " + progress.driveName() + "...";
		}
		if (progress.phase() == BackupPhase.FINISHED) {
			return "Finishing...";
		}
		return progress.totalItems() != null
				? "Backing up " + progress.processedItems() + " of " + progress.totalItems() + item
				: progress.processedItems() + " changes processed" + item;
	}

	static String multiDrive(BackupProgress progress) {
		return progress.driveName() + " — drive " + progress.driveNumber() + " of " + progress.totalDrives()
				+ ", " + progress.completedDrives() + " completed.";
	}

	static String time(BackupProgress progress, Instant now) {
		String driveText = "this drive: " + duration(Duration.between(progress.driveStartedAt(), now)) + " elapsed"
				+ (progress.driveRemaining() == null ? "" : ", ~" + duration(progress.driveRemaining()) + " left");
		if (progress.totalDrives() <= 1) {
			return driveText;
		}
		String jobText = "whole job: " + duration(Duration.between(progress.jobStartedAt(), now)) + " elapsed"
				+ (progress.jobRemaining() == null ? "" : ", ~" + duration(progress.jobRemaining()) + " left");
		return driveText + " — " + jobText;
	}

	static String duration(Duration duration) {
		long totalSeconds = Math.max(0, duration.getSeconds());
		long minutes = totalSeconds / 60;
		long seconds = totalSeconds % 60;
		return minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
	}
}
