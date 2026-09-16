package org.nm.gdrive_backup.domain.model;

import java.time.Instant;
import java.util.List;

/** What an archive's embedded manifest.json says; mirrors the archives row plus its captured files and events. */
public record ArchiveManifest(
		String scopeKey,
		ArchiveMode mode,
		RevisionMode revisionMode,
		int sequenceNumber,
		Long baseArchiveId,
		Instant createdAt,
		String fromPageToken,
		String toPageToken,
		List<ManifestFile> files,
		List<ManifestEvent> events) {

	public record ManifestFile(String fileId, String path, String revisionId, long sizeBytes) {
	}

	public record ManifestEvent(String fileId, String eventType, String oldValue, String newValue, Instant timestamp) {
	}
}
