package org.nm.gdrive_backup.domain.model;

import java.time.Instant;

public record Archive(
		Long id,
		String scopeKey,
		DriveScopeType scopeType,
		int sequenceNumber,
		Long baseArchiveId,
		ArchiveMode mode,
		RevisionMode revisionMode,
		Instant createdAt,
		String archivePath,
		String fromPageToken,
		String toPageToken,
		boolean cancelled) {
}
