package org.nm.gdrive_backup.domain.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.FileEvent;
import org.nm.gdrive_backup.domain.model.FileHistory;
import org.nm.gdrive_backup.domain.model.FileSearchResult;
import org.nm.gdrive_backup.domain.model.HistoryEntry;
import org.nm.gdrive_backup.domain.model.HistoryEntryKind;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.port.in.FileHistoryUseCase;
import org.nm.gdrive_backup.domain.port.out.ArchivePort;
import org.nm.gdrive_backup.domain.port.out.FileCapturePort;
import org.nm.gdrive_backup.domain.port.out.FileEventPort;
import org.nm.gdrive_backup.domain.port.out.FileMetadataPort;

/** Answers "what happened to this file, and which archive holds each version" from the backup database. */
public class FileHistoryService implements FileHistoryUseCase {

	static final int SEARCH_LIMIT = 200;

	private final FileMetadataPort fileMetadataPort;
	private final FileEventPort fileEventPort;
	private final FileCapturePort fileCapturePort;
	private final ArchivePort archivePort;

	public FileHistoryService(FileMetadataPort fileMetadataPort, FileEventPort fileEventPort,
			FileCapturePort fileCapturePort, ArchivePort archivePort) {
		this.fileMetadataPort = fileMetadataPort;
		this.fileEventPort = fileEventPort;
		this.fileCapturePort = fileCapturePort;
		this.archivePort = archivePort;
	}

	@Override
	public List<FileSearchResult> searchFiles(String query) {
		if (query == null || query.isBlank()) {
			return List.of();
		}
		List<StoredFile> files = fileMetadataPort.searchByName(query.trim(), SEARCH_LIMIT);
		if (files.isEmpty()) {
			return List.of();
		}
		// The same label the Archive manager shows: the drive's archive folder name.
		Map<String, String> labels = new HashMap<>();
		archivePort.findAll().forEach(archive -> labels.putIfAbsent(archive.scopeKey(),
				ArchiveCatalogService.labelOf(archive)));
		return files.stream()
				.map(file -> new FileSearchResult(file, labels.getOrDefault(file.ownerScope(), file.ownerScope())))
				.toList();
	}

	@Override
	public Optional<FileHistory> historyOf(String fileId) {
		Optional<StoredFile> file = fileMetadataPort.findByFileId(fileId);
		if (file.isEmpty()) {
			return Optional.empty();
		}
		Map<Long, Archive> archives = new HashMap<>();
		archivePort.findAll().forEach(archive -> archives.put(archive.id(), archive));
		List<HistoryEntry> entries = new ArrayList<>();
		for (FileEvent event : fileEventPort.findByFileId(fileId)) {
			Archive archive = archives.get(event.archiveId());
			boolean move = "move".equals(event.eventType());
			entries.add(new HistoryEntry(event.timestamp(), HistoryEntryKind.EVENT, event.eventType(),
					move ? folderNames(event.oldValue()) : event.oldValue(),
					move ? folderNames(event.newValue()) : event.newValue(), null, null, null,
					archive == null ? null : archive.sequenceNumber(), archive == null ? null : archive.mode(),
					archive == null ? null : archive.archivePath()));
		}
		fileCapturePort.findByFileId(fileId).forEach(capture -> {
			Archive archive = archives.get(capture.archiveId());
			entries.add(new HistoryEntry(capture.timestamp(), HistoryEntryKind.CAPTURE, null, null, null,
					capture.revisionId(), capture.sizeBytes(), capture.entryName(),
					archive == null ? null : archive.sequenceNumber(), archive == null ? null : archive.mode(),
					archive == null ? null : archive.archivePath()));
		});
		// A stable sort keeps events ahead of captures recorded at the same instant.
		entries.sort(Comparator.comparing(HistoryEntry::timestamp));
		return Optional.of(new FileHistory(file.get(), List.copyOf(entries)));
	}

	/** A move event stores comma-separated parent ids; show the folder names the database still knows. */
	private String folderNames(String parentIds) {
		if (parentIds == null || parentIds.isBlank()) {
			return "";
		}
		return java.util.Arrays.stream(parentIds.split(","))
				.map(String::trim)
				.filter(id -> !id.isEmpty())
				.map(id -> fileMetadataPort.findByFileId(id).map(StoredFile::name).orElse(id))
				.collect(Collectors.joining(", "));
	}
}
