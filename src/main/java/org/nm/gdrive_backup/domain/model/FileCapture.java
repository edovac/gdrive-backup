package org.nm.gdrive_backup.domain.model;

import java.time.Instant;

/** Index row: which archive entry holds the bytes of one captured revision. Id and archiveId are null until committed. */
public record FileCapture(
		Long id,
		String fileId,
		String revisionId,
		Instant timestamp,
		Long archiveId,
		String entryName,
		long sizeBytes) {
}
