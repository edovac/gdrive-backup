package org.nm.gdrive_backup.domain.port.in;

/** The number of files a backup downloads from Drive at once, which the admin can change for the session. */
public interface DownloadConcurrencyUseCase {

	int current();

	int minimum();

	int maximum();

	/**
	 * Applies to the next backup run. Throws {@link IllegalArgumentException} for a value outside the allowed
	 * range, and {@link IllegalStateException} while a backup is running.
	 */
	void change(int value);
}
