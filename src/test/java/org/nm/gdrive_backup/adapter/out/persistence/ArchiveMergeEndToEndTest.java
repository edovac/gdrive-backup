package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.service.ArchiveMergeService;
import org.nm.gdrive_backup.domain.service.ArchiveRunPlanner;
import org.nm.gdrive_backup.domain.service.BackupActivity;
import org.nm.gdrive_backup.domain.service.BackupCancellation;
import org.nm.gdrive_backup.domain.service.BackupProgressTracker;
import org.nm.gdrive_backup.domain.service.DriveChangeSyncService;
import org.nm.gdrive_backup.domain.service.FileContentStreamingService;
import org.nm.gdrive_backup.domain.service.InitialDriveSyncService;

/**
 * Runs the real services, writer, reader and SQLite adapters against an in-memory Drive: a full, two
 * incrementals, then a merge, and checks the merge is equivalent to a from-scratch full of the same Drive.
 */
class ArchiveMergeEndToEndTest {

	private static final String USER = FakeDrive.USER;
	private static final DriveScope SCOPE = DriveScope.personal(USER);
	private static final ServiceAccountAccess ACCESS = new ServiceAccountAccess(
			UUID.randomUUID(), USER, Instant.now().plusSeconds(3600), Set.of("drive.readonly"));
	private static final String FOLDER = FakeDrive.FOLDER;
	private static final String PDF = FakeDrive.PDF;
	private static final String DOC = FakeDrive.DOC;

	@TempDir
	Path temporaryDirectory;

	private FakeDrive drive;
	private SqliteDatabase database;
	private LocalBackupRoot root;
	private SqliteArchiveAdapter archives;
	private InitialDriveSyncService full;
	private DriveChangeSyncService incremental;
	private ArchiveMergeService merge;

	@BeforeEach
	void wire() {
		drive = new FakeDrive();
		root = new LocalBackupRoot(temporaryDirectory);
		database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		archives = new SqliteArchiveAdapter(database);
		LocalArchiveSessionAdapter sessions = new LocalArchiveSessionAdapter(root);
		SqliteSyncCommitAdapter commit = new SqliteSyncCommitAdapter(database);
		ArchiveRunPlanner planner = new ArchiveRunPlanner(archives);
		FileContentStreamingService streaming = new FileContentStreamingService(drive);
		full = new InitialDriveSyncService(drive, drive, streaming, sessions, planner, commit,
				BackupProgressTracker.NO_OP, new BackupCancellation(), () -> 1);
		incremental = new DriveChangeSyncService(drive, new SqliteSyncStateAdapter(database),
				new SqliteFileMetadataAdapter(database), streaming, sessions, planner, commit,
				BackupProgressTracker.NO_OP, new BackupCancellation(), () -> 1);
		merge = new ArchiveMergeService(archives, new LocalArchiveReaderAdapter(root), sessions, planner, commit,
				new BackupActivity(), BackupProgressTracker.NO_OP, new BackupCancellation());
	}

	private void buildInitialDrive() {
		drive.create("folder-docs", "Docs", "", FOLDER, "");
		drive.create("folder-old", "Archive", "", FOLDER, "");
		drive.create("a", "a.pdf", "folder-docs", PDF, "A1");
		drive.create("b", "b.pdf", "folder-docs", PDF, "B1");
		drive.create("c", "c.pdf", "", PDF, "C1");
		drive.create("notes", "Notes", "folder-docs", DOC, "N1");
		drive.create("old", "old.pdf", "folder-old", PDF, "O1");
		full.synchronize(ACCESS, SCOPE, null);
	}

	private void changeTheDriveTwiceAndBackUpIncrementally() {
		drive.edit("a", "A2");
		drive.rename("b", "b2.pdf");
		drive.move("c", "folder-old");
		drive.trash("old");
		drive.create("folder-new", "New", "", FOLDER, "");
		drive.create("w", "new.pdf", "folder-new", PDF, "W1");
		drive.edit("notes", "N2");
		incremental.synchronize(ACCESS, SCOPE, null);

		drive.delete("b");
		drive.delete("c");
		drive.untrash("old");
		drive.edit("w", "W2");
		incremental.synchronize(ACCESS, SCOPE, null);
	}

	@Test
	void aMergedFullExtractsToTheSameTreeAsAFromScratchFullOfTheSameDrive() throws Exception {
		buildInitialDrive();
		changeTheDriveTwiceAndBackUpIncrementally();

		Archive merged = merge.merge(SCOPE, null).archive();
		Archive scratch = full.synchronize(ACCESS, SCOPE, null).archive();

		Map<String, String> expected = new TreeMap<>(Map.of(
				"Docs/a.pdf", "A2",
				"Docs/Notes.docx", "N2",
				"Archive/old.pdf", "O1",
				"New/new.pdf", "W2"));
		assertEquals(expected, extract(merged));
		assertEquals(extract(scratch), extract(merged));
		assertEquals(ArchiveMode.MERGED_FULL, merged.mode());
		assertEquals(4, merged.sequenceNumber());
		assertNull(merged.baseArchiveId());
	}

	@Test
	void theMergeNeedsOnlyTheArchivesNotTheFileMetadataInTheDatabase() throws Exception {
		buildInitialDrive();
		changeTheDriveTwiceAndBackUpIncrementally();
		try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database.path());
				var statement = connection.createStatement()) {
			statement.executeUpdate("UPDATE files SET current_version_id = NULL");
			statement.executeUpdate("DELETE FROM file_captures");
			statement.executeUpdate("DELETE FROM file_events");
			statement.executeUpdate("DELETE FROM files");
		}

		Archive merged = merge.merge(SCOPE, null).archive();

		assertEquals(Map.of("Docs/a.pdf", "A2", "Docs/Notes.docx", "N2", "Archive/old.pdf", "O1",
				"New/new.pdf", "W2"), extract(merged));
	}

	@Test
	void incrementalBackupsContinueAfterAMergeAndCanBeMergedAgain() throws Exception {
		buildInitialDrive();
		changeTheDriveTwiceAndBackUpIncrementally();
		Archive merged = merge.merge(SCOPE, null).archive();

		drive.edit("a", "A3");
		drive.create("extra", "extra.pdf", "folder-docs", PDF, "X1");
		Archive next = incremental.synchronize(ACCESS, SCOPE, null).archive();

		assertEquals(ArchiveMode.INCREMENTAL, next.mode());
		assertEquals(5, next.sequenceNumber());
		assertEquals(merged.id(), next.baseArchiveId());

		Archive mergedAgain = merge.merge(SCOPE, null).archive();
		Archive scratch = full.synchronize(ACCESS, SCOPE, null).archive();

		assertEquals(6, mergedAgain.sequenceNumber());
		assertEquals("A3", extract(mergedAgain).get("Docs/a.pdf"));
		assertEquals("X1", extract(mergedAgain).get("Docs/extra.pdf"));
		assertEquals(extract(scratch), extract(mergedAgain));
	}

	@Test
	void aFileTrashedBeforeAFreshFullAndUntrashedAfterwardsStillReachesTheMergedArchive() throws Exception {
		drive.create("keep", "keep.pdf", "", PDF, "K1");
		drive.create("later", "later.pdf", "", PDF, "L1");
		full.synchronize(ACCESS, SCOPE, null);
		drive.trash("later");
		incremental.synchronize(ACCESS, SCOPE, null);
		// A from-scratch full leaves the trashed file out, so the new chain starts without its bytes.
		full.synchronize(ACCESS, SCOPE, null);
		drive.untrash("later");
		incremental.synchronize(ACCESS, SCOPE, null);

		Archive merged = merge.merge(SCOPE, null).archive();

		assertEquals(Map.of("keep.pdf", "K1", "later.pdf", "L1"), extract(merged));
	}

	private Map<String, String> extract(Archive archive) throws IOException {
		Map<String, String> tree = new TreeMap<>();
		try (ZipFile zip = new ZipFile(root.root().resolve(archive.archivePath()).toFile())) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory() || entry.getName().equals("manifest.json")) {
					continue;
				}
				tree.put(entry.getName(), new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
			}
		}
		return tree;
	}
}
