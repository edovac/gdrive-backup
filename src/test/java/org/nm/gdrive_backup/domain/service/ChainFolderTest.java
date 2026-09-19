package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestFile;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.service.ChainFolder.FoldedFile;

class ChainFolderTest {

	private static final String PDF = "application/pdf";
	private static final String DOC = "application/vnd.google-apps.document";
	private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

	@Test
	void theLatestRecordGivesMetadataAndTheLatestRecordWithAnEntryGivesContent() {
		Map<String, FoldedFile> state = ChainFolder.fold(List.of(
				manifest(1, withContent("f1", "A.pdf", PDF, "Docs/A.pdf", "r1", 2)),
				manifest(2, withContent("f1", "A.pdf", PDF, "content/f1", "r2", 3))));

		FoldedFile file = state.get("f1");
		assertEquals(1, file.content().archiveIndex());
		assertEquals("content/f1", file.content().entry());
		assertEquals("r2", file.content().revisionId());
		assertEquals(3L, file.content().sizeBytes());
	}

	@Test
	void aMetadataOnlyRecordChangesTheNameButKeepsTheEarlierContent() {
		Map<String, FoldedFile> state = ChainFolder.fold(List.of(
				manifest(1, withContent("f1", "Old.pdf", PDF, "Old.pdf", "r1", 2)),
				manifest(2, metadataOnly("f1", "New.pdf", List.of("folder-2"), false))));

		FoldedFile file = state.get("f1");
		assertEquals("New.pdf", file.metadata().name());
		assertEquals(List.of("folder-2"), file.metadata().parents());
		assertEquals(0, file.content().archiveIndex());
		assertEquals("Old.pdf", file.content().entry());
	}

	@Test
	void trashingKeepsTheFileAndUntrashingRestoresItsFlagWithoutLosingContent() {
		Map<String, FoldedFile> trashed = ChainFolder.fold(List.of(
				manifest(1, withContent("f1", "A.pdf", PDF, "A.pdf", "r1", 2)),
				manifest(2, metadataOnly("f1", "A.pdf", List.of(), true))));
		assertTrue(trashed.get("f1").metadata().trashed());
		assertEquals("A.pdf", trashed.get("f1").content().entry());

		Map<String, FoldedFile> untrashed = ChainFolder.fold(List.of(
				manifest(1, withContent("f1", "A.pdf", PDF, "A.pdf", "r1", 2)),
				manifest(2, metadataOnly("f1", "A.pdf", List.of(), true)),
				manifest(3, withContent("f1", "A.pdf", PDF, "content/f1", "r1", 2))));
		assertFalse(untrashed.get("f1").metadata().trashed());
		assertEquals(2, untrashed.get("f1").content().archiveIndex());
	}

	@Test
	void aRemovedRecordDeletesTheFileAndALaterRecordAddsItBackWithoutOldContent() {
		Map<String, FoldedFile> removed = ChainFolder.fold(List.of(
				manifest(1, withContent("f1", "A.pdf", PDF, "A.pdf", "r1", 2)),
				manifest(2, ManifestFile.removed("f1"))));
		assertFalse(removed.containsKey("f1"));

		Map<String, FoldedFile> readded = ChainFolder.fold(List.of(
				manifest(1, withContent("f1", "A.pdf", PDF, "A.pdf", "r1", 2)),
				manifest(2, ManifestFile.removed("f1")),
				manifest(3, metadataOnly("f1", "A.pdf", List.of(), false))));
		assertNull(readded.get("f1").content());
	}

	@Test
	void foldersAndFilesWithNoContentHaveNoContentSource() {
		Map<String, FoldedFile> state = ChainFolder.fold(List.of(manifest(1,
				metadataOnly("folder-1", "Docs", List.of(), false),
				metadataOnly("form-1", "Survey", List.of("folder-1"), false))));

		assertNull(state.get("folder-1").content());
		assertNull(state.get("form-1").content());
		assertEquals(List.of("folder-1"), state.get("form-1").metadata().parents());
	}

	@Test
	void aNativeFileKeepsItsExportFormat() {
		Map<String, FoldedFile> state = ChainFolder.fold(List.of(manifest(1,
				new ManifestFile("d1", false, "Notes", List.of(), null, DOC, false, "v3", "Notes.docx", 5L, DOCX))));

		assertEquals(DOCX, state.get("d1").content().exportMimeType());
	}

	private static ArchiveManifest manifest(int sequenceNumber, ManifestFile... files) {
		return new ArchiveManifest(DriveScope.personal("user@example.com"),
				sequenceNumber == 1 ? ArchiveMode.FULL : ArchiveMode.INCREMENTAL, RevisionMode.LATEST_ONLY,
				sequenceNumber, sequenceNumber == 1 ? null : sequenceNumber - 1, Instant.now(), null, null, List.of(),
				List.of(files), List.of());
	}

	private static ManifestFile withContent(String id, String name, String mime, String entry, String revision, long size) {
		return new ManifestFile(id, false, name, List.of(), null, mime, false, revision, entry, size, null);
	}

	private static ManifestFile metadataOnly(String id, String name, List<String> parents, boolean trashed) {
		return new ManifestFile(id, false, name, parents, null, PDF, trashed, null, null, null, null);
	}
}
