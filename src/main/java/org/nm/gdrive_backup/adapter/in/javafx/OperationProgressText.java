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

	/**
	 * One line under the progress bar: just the file's name, its full Drive path as the hover text, and how much of
	 * it has been downloaded (the final size once it is done).
	 */
	record DownloadRow(String fileId, String text, String tooltip, String sizeText, boolean finished) {
	}

	/** The files being downloaded right now, in the order they started. */
	static List<DownloadRow> downloadingRows(BackupProgress progress) {
		return progress.downloads().stream()
				.filter(download -> !download.finished())
				.sorted(Comparator.comparing(FileDownload::startedAt).thenComparing(FileDownload::fileId))
				.map(OperationProgressText::row)
				.toList();
	}

	/** The most recently downloaded files, newest first; the snapshot carries only the last few. */
	static List<DownloadRow> downloadedRows(BackupProgress progress) {
		return progress.downloads().stream()
				.filter(FileDownload::finished)
				.sorted(Comparator.comparing(FileDownload::finishedAt).reversed().thenComparing(FileDownload::fileId))
				.map(OperationProgressText::row)
				.toList();
	}

	static String downloadingHeading(int count) {
		return "Downloading (" + count + ")";
	}

	/** The count is every file finished in this drive, not just the few rows listed. */
	static String downloadedHeading(int count) {
		return String.format(Locale.ROOT, "Downloaded (%,d)", count);
	}

	static final String NOTHING_DOWNLOADING = "No downloads in progress";
	static final String NOTHING_DOWNLOADED = "Nothing downloaded yet";

	private static DownloadRow row(FileDownload download) {
		return new DownloadRow(download.fileId(),
				download.name() == null || download.name().isBlank() ? download.fileId() : download.name(),
				download.path() == null || download.path().isBlank() ? download.name() : download.path(),
				sizeText(download.bytesDownloaded(), download.totalBytes(), download.finished()),
				download.finished());
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
