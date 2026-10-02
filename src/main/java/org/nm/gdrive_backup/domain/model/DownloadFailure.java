package org.nm.gdrive_backup.domain.model;

import java.time.Instant;

/**
 * A file a backup run skipped because its content could not be downloaded or exported. The row stays {@code open}
 * until a later run captures the file, or the file stops needing a backup (deleted or trashed in Drive), or a newer
 * failure of the same file replaces it; {@code resolvedAt} is set only in the first two cases.
 *
 * @param id database id, {@code null} until stored
 * @param scopeKey the drive the file belongs to (the user's email or the Shared Drive id)
 * @param archiveId the archive the run produced, or {@code null} when the run published none
 * @param drivePath the file's full path in Drive when it failed
 */
public record DownloadFailure(Long id, String scopeKey, String fileId, String fileName, String drivePath, String reason,
		Instant failedAt, Long archiveId, boolean open, Instant resolvedAt) {

	/** A failure found by a run, not stored yet. */
	public static DownloadFailure found(String scopeKey, String fileId, String fileName, String drivePath, String reason,
			Instant failedAt) {
		return new DownloadFailure(null, scopeKey, fileId, fileName, drivePath, reason, failedAt, null, true, null);
	}
}
