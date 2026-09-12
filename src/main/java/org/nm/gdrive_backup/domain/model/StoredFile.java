package org.nm.gdrive_backup.domain.model;

public record StoredFile(
		String fileId,
		String ownerScope,
		String name,
		String parents,
		String driveId,
		String mimeType,
		boolean trashed,
		String headRevisionId,
		Long currentVersionId) {
}