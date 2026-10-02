package org.nm.gdrive_backup.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning for backup runs.
 *
 * @param downloadConcurrency how many files are downloaded from Drive at once. Downloads are latency-bound, so a
 *        few in parallel is much faster than one at a time; going too high only trips Drive's per-user rate limits.
 */
@ConfigurationProperties(prefix = "gdrive-backup.backup")
public record BackupProperties(@DefaultValue("4") int downloadConcurrency) {

	static final int MAX_DOWNLOAD_CONCURRENCY = 16;

	public BackupProperties {
		if (downloadConcurrency < 1 || downloadConcurrency > MAX_DOWNLOAD_CONCURRENCY) {
			throw new IllegalArgumentException("gdrive-backup.backup.download-concurrency must be between 1 and "
					+ MAX_DOWNLOAD_CONCURRENCY + " but was " + downloadConcurrency);
		}
	}
}
