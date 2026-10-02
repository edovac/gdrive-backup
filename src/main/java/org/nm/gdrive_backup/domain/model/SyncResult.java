package org.nm.gdrive_backup.domain.model;

import java.util.List;

/**
 * Outcome of an incremental run; {@code archive} is null when nothing changed or the run was cancelled.
 * {@code failures} are the files it skipped.
 */
public record SyncResult(DriveScope scope, int changeCount, String pageToken, Archive archive, boolean cancelled,
		List<DownloadFailure> failures) {

	public SyncResult(DriveScope scope, int changeCount, String pageToken, Archive archive, boolean cancelled) {
		this(scope, changeCount, pageToken, archive, cancelled, List.of());
	}
}
