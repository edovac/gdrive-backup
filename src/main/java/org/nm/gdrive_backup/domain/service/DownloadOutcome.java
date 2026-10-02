package org.nm.gdrive_backup.domain.service;

import java.util.LinkedHashSet;
import java.util.Set;

import org.nm.gdrive_backup.domain.model.FetchedFile;
import org.nm.gdrive_backup.domain.model.FileDownloadException;

/** What one file's fetch produced: its staged content, or the reason that file alone could not be fetched. */
record DownloadOutcome(FetchedFile fetched, FileDownloadException failure) implements AutoCloseable {

	private static final int MAX_REASON_LENGTH = 500;

	static DownloadOutcome fetched(FetchedFile fetched) {
		return new DownloadOutcome(fetched, null);
	}

	static DownloadOutcome failed(FileDownloadException failure) {
		return new DownloadOutcome(null, failure);
	}

	@Override
	public void close() {
		if (fetched != null) {
			fetched.close();
		}
	}

	/** The distinct messages along the cause chain on one line, e.g. "Unable to back up file content — 403 Forbidden". */
	static String reasonOf(Throwable failure) {
		Set<String> messages = new LinkedHashSet<>();
		for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
			String message = cause.getMessage();
			messages.add(message == null || message.isBlank() ? cause.getClass().getSimpleName()
					: message.replaceAll("\\s+", " ").trim());
		}
		String reason = String.join(" — ", messages);
		return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH - 1) + "…";
	}
}
