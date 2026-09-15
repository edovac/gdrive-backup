package org.nm.gdrive_backup.domain.model;

import java.time.Instant;

public record FileCapture(
		Long id,
		String fileId,
		String revisionId,
		Instant timestamp,
		String localPath,
		long sizeBytes,
		Long archiveId) {
}
