package org.nm.gdrive_backup.domain.model;

import java.time.Instant;

public record FileEvent(
		Long id,
		String fileId,
		String eventType,
		String oldValue,
		String newValue,
		Instant timestamp,
		Long archiveId) {
}