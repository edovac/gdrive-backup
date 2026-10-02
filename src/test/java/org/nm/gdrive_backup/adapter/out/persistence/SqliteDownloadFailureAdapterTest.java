package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.DeletionCommit;
import org.nm.gdrive_backup.domain.model.DownloadFailure;
import org.nm.gdrive_backup.domain.model.DriveScopeType;
import org.nm.gdrive_backup.domain.model.FailureChanges;
import org.nm.gdrive_backup.domain.model.PendingCommit;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.model.SyncState;

class SqliteDownloadFailureAdapterTest {

	private static final String SCOPE = "user@example.com";
	private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");

	@TempDir
	Path temporaryDirectory;

	private SqliteDatabase database;
	private SqliteSyncCommitAdapter commits;
	private SqliteDownloadFailureAdapter failures;

	@BeforeEach
	void open() {
		database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		commits = new SqliteSyncCommitAdapter(database);
		failures = new SqliteDownloadFailureAdapter(database);
	}

	@Test
	void aRunsSkippedFilesAreStoredOpenWithTheArchiveOfTheRun() {
		Archive archive = commit(archive(1), List.of(failure("a")), List.of());

		List<DownloadFailure> open = failures.findOpenByScopeKey(SCOPE);

		assertEquals(1, open.size());
		DownloadFailure stored = open.getFirst();
		assertNotNull(stored.id());
		assertEquals("a", stored.fileId());
		assertEquals("My Drive/a.pdf", stored.drivePath());
		assertEquals("forbidden", stored.reason());
		assertEquals(NOW, stored.failedAt());
		assertEquals(archive.id(), stored.archiveId());
		assertTrue(stored.open());
		assertNull(stored.resolvedAt());
		assertEquals(open, failures.findOpen());
	}

	@Test
	void aRunWithoutAnArchiveStillStoresItsFailures() {
		commit(null, List.of(failure("a")), List.of());

		assertNull(failures.findOpenByScopeKey(SCOPE).getFirst().archiveId());
	}

	@Test
	void aFileThatFailsAgainKeepsOneOpenRowAndTheOlderOneIsClosedUnresolved() throws Exception {
		commit(archive(1), List.of(failure("a")), List.of());
		Archive second = commit(archive(2), List.of(failure("a")), List.of());

		List<DownloadFailure> open = failures.findOpenByScopeKey(SCOPE);

		assertEquals(1, open.size());
		assertEquals(second.id(), open.getFirst().archiveId());
		assertEquals(2, count("download_failures"));
		assertEquals(1, count("download_failures WHERE open = 0 AND resolved_at IS NULL"));
	}

	@Test
	void resolvingAFileClosesItsOpenRowWithAResolutionTime() throws Exception {
		commit(archive(1), List.of(failure("a"), failure("b")), List.of());
		commit(archive(2), List.of(), List.of("a"));

		assertEquals(List.of("b"), failures.findOpenByScopeKey(SCOPE).stream().map(DownloadFailure::fileId).toList());
		assertEquals(1, count("download_failures WHERE open = 0 AND resolved_at IS NOT NULL"));
	}

	@Test
	void failuresOfOtherDrivesAreNotTouchedByARunOfThisOne() {
		commit(archive(1), List.of(failure("a")), List.of());
		commits.commit(new PendingCommit(null, List.of(), List.of(), List.of(), null,
				new FailureChanges("drive-2", List.of(), List.of("a"))));

		assertEquals(1, failures.findOpenByScopeKey(SCOPE).size());
	}

	@Test
	void deletingAnArchiveMovesItsFailuresToTheMergedArchive() {
		Archive first = commit(archive(1), List.of(failure("a")), List.of());
		Archive merged = commits.commit(new PendingCommit(archive(2), List.of(), List.of(), List.of(), null,
				List.of(first.id())));

		new SqliteArchiveDeletionCommitAdapter(database)
				.apply(new DeletionCommit(merged.id(), List.of(first.id()), java.util.Map.of(), List.of()));

		assertEquals(merged.id(), failures.findOpenByScopeKey(SCOPE).getFirst().archiveId());
	}

	private Archive commit(Archive archive, List<DownloadFailure> failed, List<String> resolved) {
		return commits.commit(new PendingCommit(archive, List.of(file("a"), file("b")), List.of(), List.of(),
				new SyncState(SCOPE, "token"), new FailureChanges(SCOPE, failed, resolved)));
	}

	private int count(String tableAndFilter) throws Exception {
		try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database.path());
				var statement = connection.createStatement();
				var result = statement.executeQuery("SELECT COUNT(*) FROM " + tableAndFilter)) {
			result.next();
			return result.getInt(1);
		}
	}

	private static DownloadFailure failure(String fileId) {
		return DownloadFailure.found(SCOPE, fileId, fileId + ".pdf", "My Drive/" + fileId + ".pdf", "forbidden", NOW);
	}

	private static Archive archive(int sequenceNumber) {
		return new Archive(null, SCOPE, DriveScopeType.PERSONAL, sequenceNumber, null,
				sequenceNumber == 1 ? ArchiveMode.FULL : ArchiveMode.MERGED_FULL, RevisionMode.LATEST_ONLY, NOW,
				"archives/x/archive-000" + sequenceNumber + ".zip", null, null, false);
	}

	private static StoredFile file(String id) {
		return new StoredFile(id, SCOPE, id + ".pdf", "", null, "application/pdf", false, "r1", null);
	}
}
