package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
import org.nm.gdrive_backup.domain.model.ArchiveState;
import org.nm.gdrive_backup.domain.model.DeletionPlan;
import org.nm.gdrive_backup.domain.model.DeletionResult;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.ScopeArchives;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.service.ArchiveCatalogService;
import org.nm.gdrive_backup.domain.service.ArchiveDeletionService;
import org.nm.gdrive_backup.domain.service.ArchiveMergeService;
import org.nm.gdrive_backup.domain.service.ArchiveRunPlanner;
import org.nm.gdrive_backup.domain.service.BackupActivity;
import org.nm.gdrive_backup.domain.service.BackupCancellation;
import org.nm.gdrive_backup.domain.service.BackupProgressTracker;
import org.nm.gdrive_backup.domain.service.DriveChangeSyncService;
import org.nm.gdrive_backup.domain.service.FileContentStreamingService;
import org.nm.gdrive_backup.domain.service.InitialDriveSyncService;

/** Full, incrementals, merge, then a verified deletion of the obsolete archives, with the real adapters. */
class ArchiveDeletionEndToEndTest {

	private static final String USER = FakeDrive.USER;
	private static final DriveScope SCOPE = DriveScope.personal(USER);
	private static final ServiceAccountAccess ACCESS = new ServiceAccountAccess(
			UUID.randomUUID(), USER, Instant.now().plusSeconds(3600), Set.of("drive.readonly"));
	private static final String PDF = FakeDrive.PDF;
	private static final String FOLDER = FakeDrive.FOLDER;

	@TempDir
	Path temporaryDirectory;

	private FakeDrive drive;
	private SqliteDatabase database;
	private LocalBackupRoot root;
	private SqliteArchiveAdapter archives;
	private InitialDriveSyncService full;
	private DriveChangeSyncService incremental;
	private ArchiveMergeService merge;
	private ArchiveDeletionService deletion;
	private ArchiveCatalogService catalog;

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
		LocalArchiveReaderAdapter reader = new LocalArchiveReaderAdapter(root);
		LocalArchiveStorageAdapter storage = new LocalArchiveStorageAdapter(root);
		BackupActivity activity = new BackupActivity();
		full = new InitialDriveSyncService(drive, drive, streaming, sessions, planner, commit,
				BackupProgressTracker.NO_OP, new BackupCancellation());
		incremental = new DriveChangeSyncService(drive, new SqliteSyncStateAdapter(database),
				new SqliteFileMetadataAdapter(database), streaming, sessions, planner, commit,
				BackupProgressTracker.NO_OP, new BackupCancellation());
		merge = new ArchiveMergeService(archives, reader, sessions, planner, commit, activity,
				BackupProgressTracker.NO_OP, new BackupCancellation());
		deletion = new ArchiveDeletionService(archives, reader, storage, new SqliteFileCaptureAdapter(database),
				new SqliteFileEventAdapter(database), new SqliteFileMetadataAdapter(database),
				new SqliteArchiveDeletionCommitAdapter(database), activity, BackupProgressTracker.NO_OP,
				new BackupCancellation());
		catalog = new ArchiveCatalogService(archives, storage);
	}

	@Test
	void deletingTheObsoleteArchivesKeepsTheMergedFullTheIndexAndTheHistoryAndBackupsContinue() throws Exception {
		drive.create("folder-docs", "Docs", "", FOLDER, "");
		drive.create("a", "a.pdf", "folder-docs", PDF, "A1");
		drive.create("b", "b.pdf", "folder-docs", PDF, "B1");
		full.synchronize(ACCESS, SCOPE, null);
		drive.edit("a", "A2");
		drive.rename("b", "b2.pdf");
		incremental.synchronize(ACCESS, SCOPE, null);
		drive.edit("a", "A3");
		incremental.synchronize(ACCESS, SCOPE, null);
		Archive merged = merge.merge(SCOPE, null).archive();
		int eventsBefore = count("file_events");

		DeletionPlan plan = deletion.prepare(SCOPE, null).orElseThrow();
		assertTrue(plan.verified(), plan.verificationProblems().toString());
		assertEquals(3, plan.obsolete().size());
		assertTrue(plan.lostContent().isEmpty());
		DeletionResult result = deletion.execute(SCOPE, plan);

		assertEquals(3, result.deletedFiles());
		assertTrue(result.filesThatCouldNotBeDeleted().isEmpty());
		assertTrue(result.freedBytes() > 0);
		assertEquals(List.of(merged), archives.findByScopeKey(USER));
		assertOnlyMergedArchiveOnDisk(merged);
		assertEquals(eventsBefore, count("file_events"));
		assertEquals(0, count("file_events WHERE archive_id NOT IN (SELECT id FROM archives)"));
		assertEquals(0, count("file_captures WHERE archive_id NOT IN (SELECT id FROM archives)"));
		// every file that still has a current version points at a capture the merged full really holds
		assertEquals(0, count("files WHERE current_version_id IS NOT NULL AND current_version_id NOT IN "
				+ "(SELECT id FROM file_captures)"));
		Map<String, String> tree = extract(merged);
		assertEquals(Map.of("Docs/a.pdf", "A3", "Docs/b2.pdf", "B1"), tree);

		ScopeArchives view = catalog.listScopes().getFirst();
		assertEquals(List.of(ArchiveState.CHAIN_ROOT), view.archives().stream().map(a -> a.state()).toList());
		assertFalse(view.hasObsolete());

		drive.edit("b", "B2");
		Archive next = incremental.synchronize(ACCESS, SCOPE, null).archive();
		assertEquals(merged.id(), next.baseArchiveId());
		Archive mergedAgain = merge.merge(SCOPE, null).archive();
		Archive scratch = full.synchronize(ACCESS, SCOPE, null).archive();
		assertEquals(extract(scratch), extract(mergedAgain));
		assertEquals("B2", extract(mergedAgain).get("Docs/b2.pdf"));
	}

	@Test
	void aTrashedFilesContentIsReportedAsLostAndReCapturedWhenItIsUntrashed() throws Exception {
		drive.create("keep", "keep.pdf", "", PDF, "K1");
		drive.create("later", "later.pdf", "", PDF, "L1");
		full.synchronize(ACCESS, SCOPE, null);
		drive.trash("later");
		incremental.synchronize(ACCESS, SCOPE, null);
		Archive merged = merge.merge(SCOPE, null).archive();

		DeletionPlan plan = deletion.prepare(SCOPE, null).orElseThrow();

		assertTrue(plan.verified());
		assertEquals(List.of("later"), plan.lostContent().stream().map(l -> l.fileId()).toList());
		deletion.execute(SCOPE, plan);
		assertEquals(Map.of("keep.pdf", "K1"), extract(merged));

		drive.untrash("later");
		Archive next = incremental.synchronize(ACCESS, SCOPE, null).archive();
		assertNotNull(next);
		Archive mergedAgain = merge.merge(SCOPE, null).archive();
		assertEquals(Map.of("keep.pdf", "K1", "later.pdf", "L1"), extract(mergedAgain));
	}

	@Test
	void aCorruptedMergedFullBlocksTheDeletionAndLeavesEverythingInPlace() throws Exception {
		drive.create("a", "a.pdf", "", PDF, "A1");
		full.synchronize(ACCESS, SCOPE, null);
		drive.edit("a", "A2");
		incremental.synchronize(ACCESS, SCOPE, null);
		Archive merged = merge.merge(SCOPE, null).archive();
		Files.write(root.root().resolve(merged.archivePath()), new byte[] { 1, 2, 3, 4 });

		DeletionPlan plan = deletion.prepare(SCOPE, null).orElseThrow();

		assertFalse(plan.verified());
		assertThrowsRefusal(plan);
		assertEquals(3, archives.findByScopeKey(USER).size());
		assertEquals(3, filesOnDisk());
	}

	private void assertThrowsRefusal(DeletionPlan plan) {
		org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> deletion.execute(SCOPE, plan));
	}

	private void assertOnlyMergedArchiveOnDisk(Archive merged) throws IOException {
		try (var files = Files.walk(root.root().resolve("archives"))) {
			List<Path> zips = files.filter(path -> path.toString().endsWith(".zip")).toList();
			assertEquals(1, zips.size());
			assertTrue(zips.getFirst().endsWith(Path.of(merged.archivePath()).getFileName()));
		}
	}

	private long filesOnDisk() throws IOException {
		try (var files = Files.walk(root.root().resolve("archives"))) {
			return files.filter(path -> path.toString().endsWith(".zip")).count();
		}
	}

	private int count(String tableAndWhere) throws Exception {
		try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database.path());
				var statement = connection.createStatement();
				var result = statement.executeQuery("SELECT COUNT(*) FROM " + tableAndWhere)) {
			result.next();
			return result.getInt(1);
		}
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
