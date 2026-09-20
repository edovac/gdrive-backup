package org.nm.gdrive_backup.domain.model;

import java.util.List;
import java.util.Map;

/**
 * The database side of a deletion, applied in one transaction: index rows the merged full carries are re-pointed at
 * it, the others removed, events re-pointed, and the obsolete archives' rows removed.
 * {@code obsoleteArchiveIds} are ordered newest first.
 */
public record DeletionCommit(
		long mergedArchiveId,
		List<Long> obsoleteArchiveIds,
		Map<Long, String> captureIdToNewEntry,
		List<Long> captureIdsToRemove) {
}
