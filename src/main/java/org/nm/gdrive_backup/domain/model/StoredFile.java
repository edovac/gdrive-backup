package org.nm.gdrive_backup.domain.model;

/**
 * A Drive file's metadata. {@code sizeBytes} is what Drive reports for an ordinary file and is {@code null} for
 * Google-native files (Docs, Sheets, Slides), folders, and anything read back from the database: it is not persisted,
 * only carried from a fresh listing so a download can show how much of the file has arrived.
 */
public record StoredFile(
		String fileId,
		String ownerScope,
		String name,
		String parents,
		String driveId,
		String mimeType,
		boolean trashed,
		String headRevisionId,
		Long currentVersionId,
		Long sizeBytes) {

	/** A file whose size is not known. */
	public StoredFile(String fileId, String ownerScope, String name, String parents, String driveId, String mimeType,
			boolean trashed, String headRevisionId, Long currentVersionId) {
		this(fileId, ownerScope, name, parents, driveId, mimeType, trashed, headRevisionId, currentVersionId, null);
	}
}
