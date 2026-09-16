package org.nm.gdrive_backup.domain.service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveEntry;
import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestEvent;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestFile;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.FileCapture;
import org.nm.gdrive_backup.domain.model.FileEvent;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.model.SyncResult;
import org.nm.gdrive_backup.domain.port.in.ArchivePackagingUseCase;
import org.nm.gdrive_backup.domain.port.out.ArchivePort;
import org.nm.gdrive_backup.domain.port.out.ArchiveWriterPort;
import org.nm.gdrive_backup.domain.port.out.FileCapturePort;
import org.nm.gdrive_backup.domain.port.out.FileMetadataPort;

public class ArchivePackagingService implements ArchivePackagingUseCase {

	private static final String FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";

	private final ArchivePort archivePort;
	private final ArchiveWriterPort archiveWriterPort;
	private final FileMetadataPort fileMetadataPort;
	private final FileCapturePort fileCapturePort;

	public ArchivePackagingService(ArchivePort archivePort, ArchiveWriterPort archiveWriterPort,
			FileMetadataPort fileMetadataPort, FileCapturePort fileCapturePort) {
		this.archivePort = archivePort;
		this.archiveWriterPort = archiveWriterPort;
		this.fileMetadataPort = fileMetadataPort;
		this.fileCapturePort = fileCapturePort;
	}

	@Override
	public Archive packageFullArchive(DriveScope scope, String scopeDisplayNameOrNull) {
		List<Archive> existing = archivePort.findByScopeKey(scope.key());
		int sequenceNumber = nextSequenceNumber(existing);

		List<StoredFile> allFiles = fileMetadataPort.findAllByOwnerScope(scope.key());
		Map<String, StoredFile> allFilesById = new HashMap<>();
		for (StoredFile file : allFiles) {
			allFilesById.put(file.fileId(), file);
		}
		FlatTreePathResolver resolver = new FlatTreePathResolver(allFilesById);

		List<ArchiveEntry> entries = new ArrayList<>();
		List<ManifestFile> manifestFiles = new ArrayList<>();
		for (StoredFile file : allFiles) {
			if (!isEligible(file)) {
				continue;
			}
			FileCapture capture = fileCapturePort.findById(file.currentVersionId())
					.orElseThrow(() -> new IllegalStateException(
							"Missing file capture " + file.currentVersionId() + " for file " + file.fileId()));
			String entryName = resolver.resolveEntryName(file);
			entries.add(new ArchiveEntry(entryName, capture.localPath()));
			manifestFiles.add(new ManifestFile(file.fileId(), entryName, capture.revisionId(), capture.sizeBytes()));
		}

		ArchiveManifest manifest = new ArchiveManifest(scope.key(), ArchiveMode.FULL, RevisionMode.LATEST_ONLY,
				sequenceNumber, null, Instant.now(), null, null, manifestFiles, List.of());
		String relativeTargetPath = relativeTargetPath(scope, scopeDisplayNameOrNull, sequenceNumber, ArchiveMode.FULL);
		writeArchive(relativeTargetPath, entries, manifest);

		return archivePort.save(new Archive(null, scope.key(), sequenceNumber, null, ArchiveMode.FULL,
				RevisionMode.LATEST_ONLY, manifest.createdAt(), relativeTargetPath, null, null, false));
	}

	@Override
	public Optional<Archive> packageIncrementalArchive(DriveScope scope, String scopeDisplayNameOrNull,
			SyncResult result) {
		if (result.events().isEmpty() && result.capturedContent().isEmpty()) {
			return Optional.empty();
		}
		List<Archive> existing = archivePort.findByScopeKey(scope.key());
		int sequenceNumber = nextSequenceNumber(existing);
		Long baseArchiveId = existing.stream()
				.max(java.util.Comparator.comparingInt(Archive::sequenceNumber))
				.map(Archive::id)
				.orElseThrow(() -> new IllegalStateException(
						"Incremental archive for " + scope.key() + " has no prior archive to chain from"));

		// The same file can appear more than once across change pages in one run; only the
		// last capture (the one current_version_id now points to) belongs in the delta payload.
		Map<String, FileCapture> latestCaptureByFileId = new LinkedHashMap<>();
		for (FileCapture capture : result.capturedContent()) {
			latestCaptureByFileId.put(capture.fileId(), capture);
		}

		List<ArchiveEntry> entries = new ArrayList<>();
		List<ManifestFile> manifestFiles = new ArrayList<>();
		for (FileCapture capture : latestCaptureByFileId.values()) {
			String entryName = "content/" + capture.fileId();
			entries.add(new ArchiveEntry(entryName, capture.localPath()));
			manifestFiles.add(new ManifestFile(capture.fileId(), entryName, capture.revisionId(), capture.sizeBytes()));
		}
		List<ManifestEvent> manifestEvents = new ArrayList<>();
		for (FileEvent event : result.events()) {
			manifestEvents.add(new ManifestEvent(event.fileId(), event.eventType(), event.oldValue(),
					event.newValue(), event.timestamp()));
		}

		ArchiveManifest manifest = new ArchiveManifest(scope.key(), ArchiveMode.INCREMENTAL, RevisionMode.LATEST_ONLY,
				sequenceNumber, baseArchiveId, Instant.now(), result.fromPageToken(), result.toPageToken(),
				manifestFiles, manifestEvents);
		String relativeTargetPath = relativeTargetPath(scope, scopeDisplayNameOrNull, sequenceNumber,
				ArchiveMode.INCREMENTAL);
		writeArchive(relativeTargetPath, entries, manifest);

		return Optional.of(archivePort.save(new Archive(null, scope.key(), sequenceNumber, baseArchiveId,
				ArchiveMode.INCREMENTAL, RevisionMode.LATEST_ONLY, manifest.createdAt(), relativeTargetPath,
				result.fromPageToken(), result.toPageToken(), false)));
	}

	private void writeArchive(String relativeTargetPath, List<ArchiveEntry> entries, ArchiveManifest manifest) {
		try {
			archiveWriterPort.write(relativeTargetPath, entries, manifest);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to write archive " + relativeTargetPath, exception);
		}
	}

	private static int nextSequenceNumber(List<Archive> existing) {
		// No chain_id exists yet, so this counts across the scope's whole archive history,
		// not per chain; revisit once "start a new chain on a fresh full backup" is built.
		return existing.stream().mapToInt(Archive::sequenceNumber).max().orElse(0) + 1;
	}

	private static boolean isEligible(StoredFile file) {
		return !FOLDER_MIME_TYPE.equals(file.mimeType()) && !file.trashed() && file.currentVersionId() != null;
	}

	private static String relativeTargetPath(DriveScope scope, String scopeDisplayNameOrNull, int sequenceNumber,
			ArchiveMode mode) {
		return "archives/" + ArchiveNaming.scopeFolderName(scope, scopeDisplayNameOrNull) + "/"
				+ ArchiveNaming.archiveFileName(sequenceNumber, mode);
	}
}
