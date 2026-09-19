package org.nm.gdrive_backup.domain.model;

import java.util.List;

/**
 * Everything a run changes in the database, held in memory and applied in one transaction after the
 * archive is published. {@code archiveOrNull} is null only when the run produced nothing to archive,
 * in which case there are no events or captures either and only file metadata and the cursor move.
 * {@code newSyncState} is null when the cursor must not move (a merge). {@code sourceArchiveIds} lists the
 * archives a merged full was built from.
 */
public record PendingCommit(
		Archive archiveOrNull,
		List<StoredFile> files,
		List<FileEvent> events,
		List<FileCapture> captures,
		SyncState newSyncState,
		List<Long> sourceArchiveIds) {

	/** A backup run: no source archives. */
	public PendingCommit(Archive archiveOrNull, List<StoredFile> files, List<FileEvent> events,
			List<FileCapture> captures, SyncState newSyncState) {
		this(archiveOrNull, files, events, captures, newSyncState, List.of());
	}
}
