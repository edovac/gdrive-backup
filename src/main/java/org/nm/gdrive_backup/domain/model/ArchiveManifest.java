package org.nm.gdrive_backup.domain.model;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * What an archive's embedded manifest.json says (see "Manifest format" in the requirements): enough to place
 * every file in the tree, verify the archive and re-link it into its chain without the database.
 */
public record ArchiveManifest(
		DriveScope scope,
		ArchiveMode mode,
		RevisionMode revisionMode,
		int sequenceNumber,
		Integer baseSequenceNumber,
		Instant createdAt,
		String fromPageToken,
		String toPageToken,
		List<ManifestSource> sourceArchives,
		List<ManifestFile> files,
		List<ManifestEvent> events) {

	/** An archive a merged full was built from. */
	public record ManifestSource(int sequenceNumber, String fileName) {
	}

	/**
	 * One file as the archive knows it. A {@code removed} record carries only the file id. {@code entry},
	 * {@code revisionId}, {@code sizeBytes} and {@code exportMimeType} are set only when this archive holds the
	 * file's bytes.
	 */
	public record ManifestFile(
			String fileId,
			boolean removed,
			String name,
			List<String> parents,
			String driveId,
			String mimeType,
			boolean trashed,
			String revisionId,
			String entry,
			Long sizeBytes,
			String exportMimeType) {

		public static ManifestFile of(StoredFile file, StreamedFile streamedOrNull) {
			List<String> parents = file.parents() == null || file.parents().isBlank()
					? List.of()
					: Arrays.stream(file.parents().split(",")).filter(id -> !id.isBlank()).toList();
			if (streamedOrNull == null) {
				return new ManifestFile(file.fileId(), false, file.name(), parents, file.driveId(), file.mimeType(),
						file.trashed(), null, null, null, null);
			}
			FileCapture capture = streamedOrNull.capture();
			return new ManifestFile(file.fileId(), false, file.name(), parents, file.driveId(), file.mimeType(),
					file.trashed(), capture.revisionId(), capture.entryName(), capture.sizeBytes(),
					streamedOrNull.exportMimeType());
		}

		public static ManifestFile removed(String fileId) {
			return new ManifestFile(fileId, true, null, List.of(), null, null, false, null, null, null, null);
		}
	}

	public record ManifestEvent(String fileId, String eventType, String oldValue, String newValue, Instant timestamp) {
	}
}
