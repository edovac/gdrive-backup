package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.FileCapture;
import org.nm.gdrive_backup.domain.model.FileEvent;
import org.nm.gdrive_backup.domain.model.PendingCommit;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.model.SyncState;

class SqliteSyncCommitAdapterTest {

	private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");

	@TempDir
	Path temporaryDirectory;

	private SqliteDatabase database;
	private SqliteSyncCommitAdapter adapter;

	@BeforeEach
	void createDatabase() {
		database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		adapter = new SqliteSyncCommitAdapter(database);
	}

	@Test
	void landsTheArchiveFilesEventsCapturesAndCursorTogether() {
		Archive saved = adapter.commit(new PendingCommit(archive(1, ArchiveMode.FULL, null),
				List.of(file("file-1", "Report.pdf", "revision-1")),
				List.of(new FileEvent(null, "file-1", "content", null, "revision-1", NOW, null)),
				List.of(new FileCapture(null, "file-1", "revision-1", NOW, null, "Report.pdf", 12)),
				new SyncState("user@example.com", "token-1")));

		assertNotNull(saved.id());
		assertEquals(List.of(saved), new SqliteArchiveAdapter(database).findByScopeKey("user@example.com"));
		FileCapture capture = new SqliteFileCaptureAdapter(database).findByFileId("file-1").getFirst();
		assertEquals(saved.id(), capture.archiveId());
		assertEquals("Report.pdf", capture.entryName());
		assertEquals(saved.id(), new SqliteFileEventAdapter(database).findByFileId("file-1").getFirst().archiveId());
		StoredFile stored = new SqliteFileMetadataAdapter(database).findByFileId("file-1").orElseThrow();
		assertEquals(capture.id(), stored.currentVersionId());
		assertEquals("token-1", new SqliteSyncStateAdapter(database).findByScopeKey("user@example.com")
				.orElseThrow().pageToken());
	}

	@Test
	void theLatestCaptureOfAFileBecomesItsCurrentVersion() {
		adapter.commit(new PendingCommit(archive(1, ArchiveMode.FULL, null),
				List.of(file("file-1", "Report.pdf", "revision-2")), List.of(),
				List.of(new FileCapture(null, "file-1", "revision-1", NOW, null, "a", 1),
						new FileCapture(null, "file-1", "revision-2", NOW, null, "b", 2)),
				new SyncState("user@example.com", "token-1")));

		List<FileCapture> captures = new SqliteFileCaptureAdapter(database).findByFileId("file-1");
		StoredFile stored = new SqliteFileMetadataAdapter(database).findByFileId("file-1").orElseThrow();
		assertEquals(captures.getLast().id(), stored.currentVersionId());
	}

	@Test
	void aLaterCommitWithoutNewContentKeepsTheFilesCurrentVersion() {
		adapter.commit(new PendingCommit(archive(1, ArchiveMode.FULL, null),
				List.of(file("file-1", "Report.pdf", "revision-1")), List.of(),
				List.of(new FileCapture(null, "file-1", "revision-1", NOW, null, "a", 1)),
				new SyncState("user@example.com", "token-1")));
		Long versionAfterFirst = new SqliteFileMetadataAdapter(database).findByFileId("file-1").orElseThrow()
				.currentVersionId();

		adapter.commit(new PendingCommit(archive(2, ArchiveMode.INCREMENTAL, 1L),
				List.of(file("file-1", "Renamed.pdf", "revision-1")),
				List.of(new FileEvent(null, "file-1", "rename", "Report.pdf", "Renamed.pdf", NOW, null)), List.of(),
				new SyncState("user@example.com", "token-2")));

		StoredFile stored = new SqliteFileMetadataAdapter(database).findByFileId("file-1").orElseThrow();
		assertEquals("Renamed.pdf", stored.name());
		assertEquals(versionAfterFirst, stored.currentVersionId());
	}

	@Test
	void aCommitWithoutAnArchiveMovesOnlyMetadataAndTheCursor() {
		Archive saved = adapter.commit(new PendingCommit(null, List.of(file("file-1", "Report.pdf", "revision-1")),
				List.of(), List.of(), new SyncState("user@example.com", "token-9")));

		assertNull(saved);
		assertTrue(new SqliteArchiveAdapter(database).findByScopeKey("user@example.com").isEmpty());
		assertEquals("token-9", new SqliteSyncStateAdapter(database).findByScopeKey("user@example.com")
				.orElseThrow().pageToken());
	}

	@Test
	void eventsOrCapturesWithoutAnArchiveAreRejected() {
		PendingCommit commit = new PendingCommit(null, List.of(file("file-1", "Report.pdf", "revision-1")),
				List.of(new FileEvent(null, "file-1", "rename", "a", "b", NOW, null)), List.of(),
				new SyncState("user@example.com", "token-1"));

		assertThrows(IllegalArgumentException.class, () -> adapter.commit(commit));
	}

	@Test
	void aFailureMidwayRollsBackEverythingIncludingTheCursor() {
		new SqliteSyncStateAdapter(database).save(new SyncState("user@example.com", "old-token"));
		// A null entry name violates NOT NULL after the archive, file and event rows are already written.
		PendingCommit commit = new PendingCommit(archive(1, ArchiveMode.FULL, null),
				List.of(file("file-1", "Report.pdf", "revision-1")),
				List.of(new FileEvent(null, "file-1", "content", null, "revision-1", NOW, null)),
				List.of(new FileCapture(null, "file-1", "revision-1", NOW, null, null, 12)),
				new SyncState("user@example.com", "new-token"));

		assertThrows(IllegalStateException.class, () -> adapter.commit(commit));

		assertTrue(new SqliteArchiveAdapter(database).findByScopeKey("user@example.com").isEmpty());
		assertTrue(new SqliteFileMetadataAdapter(database).findByFileId("file-1").isEmpty());
		assertTrue(new SqliteFileEventAdapter(database).findByFileId("file-1").isEmpty());
		assertEquals("old-token", new SqliteSyncStateAdapter(database).findByScopeKey("user@example.com")
				.orElseThrow().pageToken());
	}

	private static Archive archive(int sequenceNumber, ArchiveMode mode, Long baseArchiveId) {
		return new Archive(null, "user@example.com", sequenceNumber, baseArchiveId, mode, RevisionMode.LATEST_ONLY, NOW,
				"archives/x/archive-000" + sequenceNumber + ".zip", null, null, false);
	}

	private static StoredFile file(String id, String name, String revision) {
		return new StoredFile(id, "user@example.com", name, "", null, "application/pdf", false, revision, null);
	}
}
