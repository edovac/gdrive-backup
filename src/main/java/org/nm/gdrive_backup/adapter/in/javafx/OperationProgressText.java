package org.nm.gdrive_backup.adapter.in.javafx;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.nm.gdrive_backup.domain.model.BackupPhase;
import org.nm.gdrive_backup.domain.model.BackupProgress;
import org.nm.gdrive_backup.domain.model.FileDownload;

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

	/** How long a finished download stays in the list, so one that completes between two refreshes is still seen. */
	static final Duration FINISHED_VISIBLE_FOR = Duration.ofSeconds(3);

	/**
	 * One line under the progress bar: just the file's name, its full Drive path as the hover text, and how much of
	 * it has been downloaded.
	 */
	record DownloadRow(String fileId, String text, String tooltip, String sizeText, boolean finished) {
	}

	/**
	 * The downloads still running and those that finished within {@link #FINISHED_VISIBLE_FOR} of {@code now}, in
	 * the order they started, so a row keeps its place when its download finishes.
	 */
	static List<DownloadRow> downloadRows(BackupProgress progress, Instant now) {
		return progress.downloads().stream()
				.filter(download -> !download.finished()
						|| now.isBefore(download.finishedAt().plus(FINISHED_VISIBLE_FOR)))
				.sorted(Comparator.comparing(FileDownload::startedAt).thenComparing(FileDownload::fileId))
				.map(download -> new DownloadRow(download.fileId(),
						download.name() == null || download.name().isBlank() ? download.fileId() : download.name(),
						download.path() == null || download.path().isBlank() ? download.name() : download.path(),
						sizeText(download.bytesDownloaded(), download.totalBytes(), download.finished()),
						download.finished()))
				.toList();
	}

	/**
	 * How much of a file has arrived: "12.4 MB of 80.0 MB" while it downloads and Drive reported a size, otherwise just
	 * the amount (a Google Docs export has no size until it ends, and a finished file shows what it came to).
	 */
	static String sizeText(long downloaded, Long total, boolean finished) {
		if (!finished && total != null && total > 0 && total >= downloaded) {
			return bytes(downloaded) + " of " + bytes(total);
		}
		return bytes(downloaded);
	}

	/** A size in bytes, then KB, MB, GB or TB (1024-based) with one decimal. */
	static String bytes(long value) {
		if (value < 1024) {
			return value + " B";
		}
		String[] units = { "KB", "MB", "GB", "TB" };
		double scaled = value;
		int unit = -1;
		do {
			scaled /= 1024;
			unit++;
		} while (scaled >= 1024 && unit < units.length - 1);
		return String.format(Locale.ROOT, "%.1f %s", scaled, units[unit]);
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
