package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.DeletionCommit;
import org.nm.gdrive_backup.domain.model.DriveScopeType;
import org.nm.gdrive_backup.domain.model.FileCapture;
import org.nm.gdrive_backup.domain.model.FileEvent;
import org.nm.gdrive_backup.domain.model.PendingCommit;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.model.SyncState;

class SqliteArchiveDeletionCommitAdapterTest {

	private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");

	@TempDir
	Path temporaryDirectory;

	private SqliteDatabase database;
	private SqliteSyncCommitAdapter commits;
	private SqliteArchiveDeletionCommitAdapter deletion;
	private Archive full;
	private Archive incremental;
	private Archive merged;

	@BeforeEach
	void seed() {
		database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		commits = new SqliteSyncCommitAdapter(database);
		deletion = new SqliteArchiveDeletionCommitAdapter(database);
		full = commits.commit(new PendingCommit(archive(1, null, ArchiveMode.FULL),
				List.of(file("a", "a.pdf", "r1"), file("b", "b.pdf", "r1")),
				List.of(new FileEvent(null, "a", "content", null, "r1", NOW, null)),
				List.of(new FileCapture(null, "a", "r1", NOW, null, "a.pdf", 1),
						new FileCapture(null, "b", "r1", NOW, null, "b.pdf", 1)),
				new SyncState("user@example.com", "t1")));
		incremental = commits.commit(new PendingCommit(archive(2, full.id(), ArchiveMode.INCREMENTAL),
				List.of(file("a", "a.pdf", "r2")),
				List.of(new FileEvent(null, "a", "content", "r1", "r2", NOW, null)),
				List.of(new FileCapture(null, "a", "r2", NOW, null, "content/a", 2)),
				new SyncState("user@example.com", "t2")));
		merged = commits.commit(new PendingCommit(archive(3, null, ArchiveMode.MERGED_FULL), List.of(), List.of(),
				List.of(), null, List.of(full.id(), incremental.id())));
	}

	@Test
	void repointsCarriedCapturesRemovesTheRestKeepsEventsAndDropsTheObsoleteArchives() throws Exception {
		SqliteFileCaptureAdapter captures = new SqliteFileCaptureAdapter(database);
		FileCapture aR1 = captures.findByFileId("a").get(0);
		FileCapture aR2 = captures.findByFileId("a").get(1);
		FileCapture bR1 = captures.findByFileId("b").getFirst();
		Map<Long, String> repoint = new LinkedHashMap<>();
		repoint.put(aR2.id(), "Docs/a.pdf");
		repoint.put(bR1.id(), "b.pdf");

		deletion.apply(new DeletionCommit(merged.id(), List.of(incremental.id(), full.id()), repoint,
				List.of(aR1.id())));

		List<FileCapture> aCaptures = captures.findByFileId("a");
		assertEquals(1, aCaptures.size());
		assertEquals(merged.id(), aCaptures.getFirst().archiveId());
		assertEquals("Docs/a.pdf", aCaptures.getFirst().entryName());
		assertEquals(merged.id(), captures.findByFileId("b").getFirst().archiveId());
		assertEquals("b.pdf", captures.findByFileId("b").getFirst().entryName());
		assertEquals(List.of(merged), new SqliteArchiveAdapter(database).findByScopeKey("user@example.com"));
		assertEquals(0, count("archive_sources"));
		List<FileEvent> events = new SqliteFileEventAdapter(database).findByFileId("a");
		assertEquals(2, events.size());
		assertTrue(events.stream().allMatch(event -> event.archiveId().equals(merged.id())));
		// a's current version was the removed r1 capture only if it pointed there; it points at r2, which stays
		assertEquals(aR2.id(), new SqliteFileMetadataAdapter(database).findByFileId("a").orElseThrow().currentVersionId());
	}

	@Test
	void aRemovedCurrentCaptureClearsTheFilesCurrentVersion() throws Exception {
		SqliteFileCaptureAdapter captures = new SqliteFileCaptureAdapter(database);
		FileCapture bR1 = captures.findByFileId("b").getFirst();
		assertEquals(bR1.id(), new SqliteFileMetadataAdapter(database).findByFileId("b").orElseThrow().currentVersionId());

		deletion.apply(new DeletionCommit(merged.id(), List.of(incremental.id(), full.id()), Map.of(),
				List.of(bR1.id())));

		assertNull(new SqliteFileMetadataAdapter(database).findByFileId("b").orElseThrow().currentVersionId());
		assertTrue(captures.findByFileId("b").isEmpty());
	}

	@Test
	void aFailureRollsBackEveryChange() throws Exception {
		SqliteFileCaptureAdapter captures = new SqliteFileCaptureAdapter(database);
		FileCapture aR1 = captures.findByFileId("a").get(0);
		FileCapture aR2 = captures.findByFileId("a").get(1);
		Map<Long, String> repoint = new LinkedHashMap<>();
		repoint.put(aR2.id(), "Docs/a.pdf");
		repoint.put(aR1.id(), null); // entry_name is NOT NULL, so this fails after the first re-point succeeded

		assertThrows(IllegalStateException.class, () -> deletion.apply(new DeletionCommit(merged.id(),
				List.of(incremental.id(), full.id()), repoint, List.of())));

		assertEquals(3, new SqliteArchiveAdapter(database).findByScopeKey("user@example.com").size());
		assertEquals(incremental.id(), captures.findByFileId("a").get(1).archiveId());
		assertEquals("content/a", captures.findByFileId("a").get(1).entryName());
		assertEquals(2, count("archive_sources"));
	}

	private int count(String table) throws Exception {
		try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database.path());
				var statement = connection.createStatement();
				var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
			result.next();
			return result.getInt(1);
		}
	}

	private static Archive archive(int sequenceNumber, Long baseId, ArchiveMode mode) {
		return new Archive(null, "user@example.com", DriveScopeType.PERSONAL, sequenceNumber, baseId, mode,
				RevisionMode.LATEST_ONLY, NOW, "archives/x/archive-000" + sequenceNumber + ".zip", null, null, false);
	}

	private static StoredFile file(String id, String name, String revision) {
		return new StoredFile(id, "user@example.com", name, "", null, "application/pdf", false, revision, null);
	}
}
