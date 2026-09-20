package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.DriveScopeType;
import org.nm.gdrive_backup.domain.model.FileCapture;
import org.nm.gdrive_backup.domain.model.FileEvent;
import org.nm.gdrive_backup.domain.model.FileHistory;
import org.nm.gdrive_backup.domain.model.HistoryEntry;
import org.nm.gdrive_backup.domain.model.HistoryEntryKind;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.port.out.ArchivePort;
import org.nm.gdrive_backup.domain.port.out.FileCapturePort;
import org.nm.gdrive_backup.domain.port.out.FileEventPort;
import org.nm.gdrive_backup.domain.port.out.FileMetadataPort;

class FileHistoryServiceTest {

	private static final Instant T1 = Instant.parse("2026-01-01T10:00:00Z");
	private static final Instant T2 = Instant.parse("2026-01-02T10:00:00Z");
	private static final Instant T3 = Instant.parse("2026-01-03T10:00:00Z");

	private final FileMetadataPort metadataPort = mock(FileMetadataPort.class);
	private final FileEventPort eventPort = mock(FileEventPort.class);
	private final FileCapturePort capturePort = mock(FileCapturePort.class);
	private final ArchivePort archivePort = mock(ArchivePort.class);
	private final FileHistoryService service = new FileHistoryService(metadataPort, eventPort, capturePort,
			archivePort);

	@Test
	void mergesEventsAndCapturesInTimeOrderWithTheirArchive() {
		StoredFile file = file("file-1", "Report");
		when(metadataPort.findByFileId("file-1")).thenReturn(Optional.of(file));
		when(archivePort.findAll()).thenReturn(List.of(archive(1L, 1, ArchiveMode.FULL),
				archive(2L, 2, ArchiveMode.INCREMENTAL)));
		when(capturePort.findByFileId("file-1")).thenReturn(List.of(
				new FileCapture(1L, "file-1", "rev-1", T1, 1L, "Report", 100),
				new FileCapture(2L, "file-1", "rev-2", T3, 2L, "content/file-1", 120)));
		when(eventPort.findByFileId("file-1")).thenReturn(List.of(
				new FileEvent(1L, "file-1", "rename", "Report", "Report v2", T2, 2L)));

		FileHistory history = service.historyOf("file-1").orElseThrow();

		assertEquals(file, history.file());
		assertEquals(List.of(HistoryEntryKind.CAPTURE, HistoryEntryKind.EVENT, HistoryEntryKind.CAPTURE),
				history.entries().stream().map(HistoryEntry::kind).toList());
		HistoryEntry first = history.entries().get(0);
		assertEquals("rev-1", first.revisionId());
		assertEquals(1, first.archiveSequence());
		assertEquals(ArchiveMode.FULL, first.archiveMode());
		HistoryEntry rename = history.entries().get(1);
		assertEquals("rename", rename.eventType());
		assertEquals("Report v2", rename.newValue());
		assertEquals(2, rename.archiveSequence());
		assertEquals("content/file-1", history.entries().get(2).entryName());
	}

	@Test
	void resolvesMoveParentIdsToFolderNamesAndFallsBackToTheId() {
		when(metadataPort.findByFileId("file-1")).thenReturn(Optional.of(file("file-1", "Report")));
		when(metadataPort.findByFileId("folder-a")).thenReturn(Optional.of(file("folder-a", "Finance")));
		when(metadataPort.findByFileId("folder-b")).thenReturn(Optional.empty());
		when(archivePort.findAll()).thenReturn(List.of());
		when(capturePort.findByFileId("file-1")).thenReturn(List.of());
		when(eventPort.findByFileId("file-1")).thenReturn(List.of(
				new FileEvent(1L, "file-1", "move", "folder-a", "folder-b,folder-a", T1, 9L)));

		HistoryEntry move = service.historyOf("file-1").orElseThrow().entries().get(0);

		assertEquals("Finance", move.oldValue());
		assertEquals("folder-b, Finance", move.newValue());
		assertNull(move.archiveSequence());
	}

	@Test
	void anUnknownFileHasNoHistory() {
		when(metadataPort.findByFileId("nope")).thenReturn(Optional.empty());

		assertTrue(service.historyOf("nope").isEmpty());
	}

	@Test
	void aBlankSearchDoesNotQueryTheDatabase() {
		assertTrue(service.searchFiles("  ").isEmpty());
		assertTrue(service.searchFiles(null).isEmpty());
		verifyNoInteractions(metadataPort);
	}

	@Test
	void searchTrimsTheQueryAndCapsTheResults() {
		service.searchFiles(" budget ");

		verify(metadataPort).searchByName("budget", FileHistoryService.SEARCH_LIMIT);
	}

	private static StoredFile file(String id, String name) {
		return new StoredFile(id, "user@example.com", name, "root", null, "text/plain", false, "rev", null);
	}

	private static Archive archive(long id, int sequence, ArchiveMode mode) {
		return new Archive(id, "user@example.com", DriveScopeType.PERSONAL, sequence, null, mode,
				RevisionMode.LATEST_ONLY, T1, "archive-" + sequence + ".zip", null, null, false);
	}
}
