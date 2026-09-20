package org.nm.gdrive_backup.domain.model;

import java.time.Instant;

/**
 * One line of a file's history: either an operation ({@code eventType} with its old and new value) or a captured
 * revision ({@code revisionId}, {@code sizeBytes}, {@code entryName}). The archive fields say which archive recorded
 * it and are null when the row's archive is no longer known.
 */
public record HistoryEntry(
		Instant timestamp,
		HistoryEntryKind kind,
		String eventType,
		String oldValue,
		String newValue,
		String revisionId,
		Long sizeBytes,
		String entryName,
		Integer archiveSequence,
		ArchiveMode archiveMode,
		String archivePath) {
}
