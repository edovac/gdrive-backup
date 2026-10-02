package org.nm.gdrive_backup.adapter.in.javafx;

import java.time.ZoneId;
import java.util.List;

import org.nm.gdrive_backup.domain.model.DownloadFailure;

/** Wording for the failed-files report, kept free of JavaFX so it can be unit tested. */
public final class FailedFilesText {

	/** How many skipped files the end-of-backup summary names per drive before pointing at the full report. */
	static final int SUMMARY_LIMIT = 10;
	private static final int SUMMARY_REASON_LENGTH = 160;

	private FailedFilesText() {
	}

	/** The skipped files of one drive for the summary: the first few with their reason, then how many more. */
	static String summaryLines(List<DownloadFailure> failures) {
		StringBuilder text = new StringBuilder();
		for (DownloadFailure failure : failures.subList(0, Math.min(failures.size(), SUMMARY_LIMIT))) {
			text.append("\n    Not backed up: ").append(failure.drivePath()).append(" — ")
					.append(shorten(failure.reason(), SUMMARY_REASON_LENGTH));
		}
		if (failures.size() > SUMMARY_LIMIT) {
			text.append("\n    … and ").append(failures.size() - SUMMARY_LIMIT)
					.append(" more (see the Failed files tab)");
		}
		return text.toString();
	}

	static String heading(int count) {
		return count == 0 ? "No failed files" : count + " file(s) could not be backed up";
	}

	static String when(DownloadFailure failure, ZoneId zone) {
		return ArchiveManagerText.created(failure.failedAt(), zone);
	}

	static String shorten(String text, int maxLength) {
		return text.length() <= maxLength ? text : text.substring(0, maxLength - 1) + "…";
	}
}
