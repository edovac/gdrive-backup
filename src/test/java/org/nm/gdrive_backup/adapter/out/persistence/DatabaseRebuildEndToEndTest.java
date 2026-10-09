package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.DatabaseRebuildResult;
import org.nm.gdrive_backup.domain.model.DatabaseStatus;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.PersonalDriveContent;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.service.ArchiveMergeService;
import org.nm.gdrive_backup.domain.service.ArchiveRunPlanner;
import org.nm.gdrive_backup.domain.service.BackupActivity;
import org.nm.gdrive_backup.domain.service.BackupCancellation;
import org.nm.gdrive_backup.domain.service.BackupProgressTracker;
import org.nm.gdrive_backup.domain.service.DatabaseRebuildService;
import org.nm.gdrive_backup.domain.service.DriveChangeSyncService;
import org.nm.gdrive_backup.domain.service.FileContentStreamingService;
import org.nm.gdrive_backup.domain.service.InitialDriveSyncService;

/** Backs a fake Drive up for real, throws the database away, and checks the archives alone bring it back. */
class DatabaseRebuildEndToEndTest {

	private static final String USER = FakeDrive.USER;
	private static final DriveScope SCOPE = DriveScope.personal(USER);
	private static final ServiceAccountAccess ACCESS = new ServiceAccountAccess(
			UUID.randomUUID(), USER, Instant.now().plusSeconds(3600), Set.of("drive.readonly"));
	private static final String FOLDER = FakeDrive.FOLDER;
	private static final String PDF = FakeDrive.PDF;

	@TempDir
	Path temporaryDirectory;

	private FakeDrive drive;
	private SqliteDatabase database;
	private SqliteArchiveAdapter archives;
	private InitialDriveSyncService full;
	private DriveChangeSyncService incremental;
	private ArchiveMergeService merge;
	private DatabaseRebuildService rebuild;

	@BeforeEach
	void wire() {
		drive = new FakeDrive();
		LocalBackupRoot root = new LocalBackupRoot(temporaryDirectory);
		database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		archives = new SqliteArchiveAdapter(database);
		LocalArchiveSessionAdapter sessions = new LocalArchiveSessionAdapter(root);
		SqliteSyncCommitAdapter commit = new SqliteSyncCommitAdapter(database);
		ArchiveRunPlanner planner = new ArchiveRunPlanner(archives);
		FileContentStreamingService streaming = new FileContentStreamingService(drive);
		full = new InitialDriveSyncService(drive, drive, streaming, sessions, planner, commit,
				BackupProgressTracker.NO_OP, new BackupCancellation(), () -> 1, () -> PersonalDriveContent.OWNED_ONLY);
		incremental = new DriveChangeSyncService(drive, new SqliteSyncStateAdapter(database),
				new SqliteFileMetadataAdapter(database), streaming, sessions, planner, commit,
				BackupProgressTracker.NO_OP, new BackupCancellation(), () -> 1, () -> PersonalDriveContent.OWNED_ONLY);
		LocalArchiveReaderAdapter reader = new LocalArchiveReaderAdapter(root);
		merge = new ArchiveMergeService(archives, reader, sessions, planner, commit, new BackupActivity(),
				BackupProgressTracker.NO_OP, new BackupCancellation());
		rebuild = new DatabaseRebuildService(new LocalArchiveScanAdapter(root), reader,
				new SqliteDatabaseRebuildAdapter(database), commit, new SqliteDriveMetadataAdapter(database),
				new BackupActivity(), BackupProgressTracker.NO_OP, new BackupCancellation());
	}

	private void backUpADriveThatChanges() {
		drive.create("folder-docs", "Docs", "", FOLDER, "");
		drive.create("a", "a.pdf", "folder-docs", PDF, "A1");
		drive.create("b", "b.pdf", "folder-docs", PDF, "B1");
		full.synchronize(ACCESS, SCOPE, null);

		drive.edit("a", "A2");
		drive.rename("b", "b2.pdf");
		drive.create("c", "c.pdf", "", PDF, "C1");
		incremental.synchronize(ACCESS, SCOPE, null);

		drive.trash("b");
		drive.edit("c", "C2");
		incremental.synchronize(ACCESS, SCOPE, null);
	}

	private Map<String, Long> tableCounts() throws Exception {
		Map<String, Long> counts = new LinkedHashMap<>();
		try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.path());
				var statement = connection.createStatement()) {
			for (String table : List.of("archives", "files", "file_captures", "file_events", "archive_sources")) {
				try (var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
					result.next();
					counts.put(table, result.getLong(1));
				}
			}
		}
		return counts;
	}

	private String savedCursor() throws Exception {
		try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.path());
				var statement = connection.createStatement();
				var result = statement.executeQuery("SELECT page_token FROM sync_state WHERE scope_key = '" + USER + "'")) {
			return result.next() ? result.getString(1) : null;
		}
	}

	@Test
	void aMissingDatabaseComesBackWithTheSameHistoryAndTheCursor() throws Exception {
		backUpADriveThatChanges();
		Map<String, Long> before = tableCounts();
		String cursorBefore = savedCursor();
		Files.delete(database.path());
		assertEquals(DatabaseStatus.MISSING, rebuild.status());

		DatabaseRebuildResult result = rebuild.rebuild(false);

		assertFalse(result.cancelled());
		assertEquals(3, result.archivesRestored());
		assertEquals(List.of(), result.problems());
		assertEquals(DatabaseStatus.USABLE, rebuild.status());
		assertEquals(before, tableCounts());
		assertEquals(cursorBefore, savedCursor());
	}

	@Test
	void incrementalBackupsContinueTheChainAfterARebuild() throws Exception {
		backUpADriveThatChanges();
		Files.delete(database.path());
		rebuild.rebuild(false);

		drive.edit("a", "A3");
		Archive next = incremental.synchronize(ACCESS, SCOPE, null).archive();

		assertEquals(ArchiveMode.INCREMENTAL, next.mode());
		assertEquals(4, next.sequenceNumber());
		assertEquals(archives.findByScopeKey(USER).stream()
				.filter(archive -> archive.sequenceNumber() == 3).findFirst().orElseThrow().id(), next.baseArchiveId());
	}

	@Test
	void aCorruptDatabaseIsKeptAsABackupAndReplaced() throws Exception {
		backUpADriveThatChanges();
		Files.writeString(database.path(), "this is not a sqlite database at all, just text".repeat(50));
		assertEquals(DatabaseStatus.UNUSABLE, rebuild.status());

		DatabaseRebuildResult result = rebuild.rebuild(false);

		assertTrue(result.previousDatabaseBackup().endsWith(".bak"));
		assertTrue(Files.exists(temporaryDirectory.resolve(result.previousDatabaseBackup())));
		assertEquals(DatabaseStatus.USABLE, rebuild.status());
		assertEquals(3L, tableCounts().get("archives"));
	}

	@Test
	void aUsableDatabaseIsOnlyReplacedWhenTheCallerConfirms() throws Exception {
		backUpADriveThatChanges();

		assertThrows(IllegalStateException.class, () -> rebuild.rebuild(false));

		DatabaseRebuildResult result = rebuild.rebuild(true);
		assertEquals(3, result.archivesRestored());
		assertTrue(result.previousDatabaseBackup().endsWith(".bak"));
	}

	@Test
	void anUnreadableArchiveIsNamedAndTheRestIsRestored() throws Exception {
		backUpADriveThatChanges();
		Path second = archiveFile("archive-0002-incremental.zip");
		Files.writeString(second, "not a zip");
		Files.delete(database.path());

		DatabaseRebuildResult result = rebuild.rebuild(false);

		assertEquals(2, result.archivesRestored());
		assertTrue(result.problems().stream().anyMatch(problem -> problem.contains("archive-0002-incremental.zip")),
				result.problems().toString());
		// Archive 3 chains onto the unreadable archive 2, so it is restored unlinked and says so.
		assertTrue(result.problems().stream().anyMatch(problem -> problem.contains("archive-0003-incremental.zip")),
				result.problems().toString());
	}

	@Test
	void aMergedFullRootKeepsItsContentRecordsAfterTheObsoleteArchivesAreGone() throws Exception {
		backUpADriveThatChanges();
		Archive merged = merge.merge(SCOPE, null).archive();
		for (String name : List.of("archive-0001-full.zip", "archive-0002-incremental.zip",
				"archive-0003-incremental.zip")) {
			Files.delete(archiveFile(name));
		}
		Files.delete(database.path());

		DatabaseRebuildResult result = rebuild.rebuild(false);

		assertEquals(1, result.archivesRestored());
		assertEquals(merged.sequenceNumber(), archives.findByScopeKey(USER).getFirst().sequenceNumber());
		assertTrue(tableCounts().get("file_captures") > 0);
		// The merged full carries the cursor of the last incremental, so the next backup continues from it.
		assertEquals(merged.toPageToken(), savedCursor());
	}

	@Test
	void aFullBackupAloneKeepsItsCursorSoTheNextIncrementalChainsOntoIt() throws Exception {
		drive.create("a", "a.pdf", "", PDF, "A1");
		full.synchronize(ACCESS, SCOPE, null);
		String cursorBefore = savedCursor();
		Files.delete(database.path());

		DatabaseRebuildResult result = rebuild.rebuild(false);

		assertEquals(List.of(), result.scopesWithoutCursor());
		assertEquals(cursorBefore, savedCursor());

		drive.edit("a", "A2");
		Archive next = incremental.synchronize(ACCESS, SCOPE, null).archive();

		assertEquals(ArchiveMode.INCREMENTAL, next.mode());
		assertEquals(2, next.sequenceNumber());
		assertEquals(archives.findByScopeKey(USER).getFirst().id(), next.baseArchiveId());
	}

	private Path archiveFile(String name) throws Exception {
		try (Stream<Path> files = Files.walk(temporaryDirectory.resolve("archives"))) {
			return files.filter(path -> path.getFileName().toString().equals(name)).findFirst().orElseThrow();
		}
	}
}
