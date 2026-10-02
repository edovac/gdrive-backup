package org.nm.gdrive_backup.configuration;

import java.util.Objects;

import org.nm.gdrive_backup.domain.model.PersonalDriveContent;
import org.nm.gdrive_backup.domain.service.DownloadConcurrencyService;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning for backup runs.
 *
 * @param downloadConcurrency how many files are downloaded from Drive at once when the app starts; the admin can
 *        change it for the session in Settings. Downloads are latency-bound, so a few in parallel is much faster
 *        than one at a time; going too high only trips Drive's per-user rate limits.
 * @param personalDriveContent which files a personal drive backup takes when the app starts: only the user's own
 *        files, or also those others shared with them; the admin can change it for the session in Settings.
 */
@ConfigurationProperties(prefix = "gdrive-backup.backup")
public record BackupProperties(@DefaultValue("4") int downloadConcurrency,
		@DefaultValue("OWNED_ONLY") PersonalDriveContent personalDriveContent) {

	public BackupProperties {
		if (downloadConcurrency < DownloadConcurrencyService.MINIMUM
				|| downloadConcurrency > DownloadConcurrencyService.MAXIMUM) {
			throw new IllegalArgumentException("gdrive-backup.backup.download-concurrency must be between "
					+ DownloadConcurrencyService.MINIMUM + " and " + DownloadConcurrencyService.MAXIMUM + " but was "
					+ downloadConcurrency);
		}
		Objects.requireNonNull(personalDriveContent, "gdrive-backup.backup.personal-drive-content must be set");
	}
}
