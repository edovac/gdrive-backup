package org.nm.gdrive_backup.domain.service;

import org.nm.gdrive_backup.domain.port.in.DownloadConcurrencyUseCase;

/**
 * Holds the session's download concurrency. The sync services read {@link #current} when a run starts, and a change
 * is refused while a backup runs, so one run never changes parallelism half way through.
 */
public class DownloadConcurrencyService implements DownloadConcurrencyUseCase {

	public static final int MINIMUM = 1;
	/** Past this, extra parallelism only trips Drive's per-user rate limits. */
	public static final int MAXIMUM = 16;

	private final BackupActivity backupActivity;
	private volatile int current;

	public DownloadConcurrencyService(int initial, BackupActivity backupActivity) {
		this.current = requireInRange(initial);
		this.backupActivity = backupActivity;
	}

	@Override
	public int current() {
		return current;
	}

	@Override
	public int minimum() {
		return MINIMUM;
	}

	@Override
	public int maximum() {
		return MAXIMUM;
	}

	@Override
	public void change(int value) {
		int target = requireInRange(value);
		backupActivity.changeLocations(() -> current = target);
	}

	private static int requireInRange(int value) {
		if (value < MINIMUM || value > MAXIMUM) {
			throw new IllegalArgumentException(
					"Download concurrency must be between " + MINIMUM + " and " + MAXIMUM + " but was " + value);
		}
		return value;
	}
}
