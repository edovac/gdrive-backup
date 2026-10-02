package org.nm.gdrive_backup.domain.service;

import java.util.ArrayList;
import java.util.List;

import org.nm.gdrive_backup.domain.model.DownloadFailure;

/**
 * Collects the files a run skips and ends the run when skipping stops looking like isolated bad files: too many
 * failures in total, or a long unbroken streak of them (an outage or a revoked permission, not a few bad files). An
 * archive committed in either case would be hollow yet look complete. Used on the single thread that writes the
 * archive, in the order downloads complete.
 */
final class DownloadFailureLimit {

	/** How many downloads in a row may fail before the run is judged to be hitting an outage. */
	static final int MAX_CONSECUTIVE_FAILURES = 10;

	/** The total used where no limit is configured. */
	static final int DEFAULT_MAX_FAILURES = 50;

	private final int maxFailures;
	private final List<DownloadFailure> failures = new ArrayList<>();
	private int consecutive;

	DownloadFailureLimit(int maxFailures) {
		this.maxFailures = maxFailures;
	}

	void succeeded() {
		consecutive = 0;
	}

	/** Records the skipped file, or throws when this failure takes the run past a limit. */
	void failed(DownloadFailure failure) {
		failures.add(failure);
		consecutive++;
		if (failures.size() > maxFailures) {
			throw new DownloadFailureLimitException("Stopped after " + failures.size() + " files could not be "
					+ "downloaded (the limit is " + maxFailures + "). Last: " + describe(failure));
		}
		if (consecutive >= MAX_CONSECUTIVE_FAILURES) {
			throw new DownloadFailureLimitException("Stopped because the last " + consecutive
					+ " downloads in a row failed. Last: " + describe(failure));
		}
	}

	List<DownloadFailure> failures() {
		return List.copyOf(failures);
	}

	private static String describe(DownloadFailure failure) {
		return failure.drivePath() + " — " + failure.reason();
	}

	/** The run is ended on purpose; nothing is committed and it replays from its last cursor. */
	static final class DownloadFailureLimitException extends IllegalStateException {

		DownloadFailureLimitException(String message) {
			super(message);
		}
	}
}
