package org.nm.gdrive_backup.domain.model;

import java.util.List;

/**
 * Outcome of a full run; {@code archive} and {@code pageToken} are null when the run was cancelled.
 * {@code failures} are the files it skipped.
 */
public record InitialSyncResult(DriveScope scope, int fileCount, String pageToken, Archive archive, boolean cancelled,
		List<DownloadFailure> failures) {

	public InitialSyncResult(DriveScope scope, int fileCount, String pageToken, Archive archive, boolean cancelled) {
		this(scope, fileCount, pageToken, archive, cancelled, List.of());
	}
}
