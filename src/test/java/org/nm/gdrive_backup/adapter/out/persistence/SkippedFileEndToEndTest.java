package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import org.nm.gdrive_backup.domain.model.DeletionPlan;
import org.nm.gdrive_backup.domain.model.DownloadFailure;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.InitialSyncResult;
import org.nm.gdrive_backup.domain.model.PersonalDriveContent;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.SyncResult;
import org.nm.gdrive_backup.domain.service.ArchiveDeletionService;
import org.nm.gdrive_backup.domain.service.ArchiveMergeService;
import org.nm.gdrive_backup.domain.service.ArchiveRunPlanner;
import org.nm.gdrive_backup.domain.service.BackupActivity;
import org.nm.gdrive_backup.domain.service.BackupCancellation;
import org.nm.gdrive_backup.domain.service.BackupProgressTracker;
import org.nm.gdrive_backup.domain.service.DriveChangeSyncService;
import org.nm.gdrive_backup.domain.service.FileContentStreamingService;
import org.nm.gdrive_backup.domain.service.InitialDriveSyncService;

/**
 * A file Drive will not give is skipped and reported, retried by the following runs, and a merge and a verified
 * deletion in between keep the chain consistent, with the real adapters.
 */
class SkippedFileEndToEndTest {

	private static final String USER = FakeDrive.USER;
	private static final DriveScope SCOPE = DriveScope.personal(USER);
	private static final ServiceAccountAccess ACCESS = new ServiceAccountAccess(
			UUID.randomUUID(), USER, Instant.now().plusSeconds(3600), Set.of("drive.readonly"));
	private static final String PDF = FakeDrive.PDF;

	@TempDir
	Path temporaryDirectory;

	private FakeDrive drive;
	private SqliteDatabase database;
	private LocalBackupRoot root;
	private SqliteDownloadFailureAdapter failures;
	private InitialDriveSyncService full;
	private DriveChangeSyncService incremental;
	private ArchiveMergeService merge;
	private ArchiveDeletionService deletion;

	@BeforeEach
	void wire() {
		drive = new FakeDrive();
		root = new LocalBackupRoot(temporaryDirectory);
		database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		SqliteArchiveAdapter archives = new SqliteArchiveAdapter(database);
		failures = new SqliteDownloadFailureAdapter(database);
		LocalArchiveSessionAdapter sessions = new LocalArchiveSessionAdapter(root);
		SqliteSyncCommitAdapter commit = new SqliteSyncCommitAdapter(database);
		ArchiveRunPlanner planner = new ArchiveRunPlanner(archives);
		FileContentStreamingService streaming = new FileContentStreamingService(drive);
		LocalArchiveReaderAdapter reader = new LocalArchiveReaderAdapter(root);
		LocalArchiveStorageAdapter storage = new LocalArchiveStorageAdapter(root);
		BackupActivity activity = new BackupActivity();
		full = new InitialDriveSyncService(drive, drive, streaming, sessions, planner, commit,
				BackupProgressTracker.NO_OP, new BackupCancellation(), () -> 1,
				() -> PersonalDriveContent.OWNED_ONLY, failures, () -> 50);
		incremental = new DriveChangeSyncService(drive, new SqliteSyncStateAdapter(database),
				new SqliteFileMetadataAdapter(database), streaming, sessions, planner, commit,
				BackupProgressTracker.NO_OP, new BackupCancellation(), () -> 1,
				() -> PersonalDriveContent.OWNED_ONLY, failures, () -> 50);
		merge = new ArchiveMergeService(archives, reader, sessions, planner, commit, activity,
				BackupProgressTracker.NO_OP, new BackupCancellation());
		deletion = new ArchiveDeletionService(archives, reader, storage, new SqliteFileCaptureAdapter(database),
				new SqliteFileEventAdapter(database), new SqliteFileMetadataAdapter(database),
				new SqliteArchiveDeletionCommitAdapter(database), activity, BackupProgressTracker.NO_OP,
				new BackupCancellation());
	}

	@Test
	void aFileNeverCapturedIsReportedKeptOpenThroughMergeAndDeletionAndBackedUpOnceDriveAllowsIt() throws Exception {
		drive.create("a", "a.pdf", "", PDF, "A1");
		drive.create("b", "b.pdf", "", PDF, "B1");
		drive.block("b");

		InitialSyncResult first = full.synchronize(ACCESS, SCOPE, null);

		assertEquals(List.of("b"), first.failures().stream().map(DownloadFailure::fileId).toList());
		assertEquals(Map.of("a.pdf", "A1"), extract(first.archive()));
		DownloadFailure stored = failures.findOpenByScopeKey(USER).getFirst();
		assertEquals("My Drive/b.pdf", stored.drivePath());
		assertTrue(stored.reason().contains("downloading is disabled"), stored.reason());
		assertEquals(first.archive().id(), stored.archiveId());

		drive.edit("a", "A2");
		SyncResult second = incremental.synchronize(ACCESS, SCOPE, null);

		assertEquals(Map.of("content/a", "A2"), extract(second.archive()));
		assertEquals(1, failures.findOpenByScopeKey(USER).size(), "retried and still failing: one open row");
		assertEquals(second.archive().id(), failures.findOpenByScopeKey(USER).getFirst().archiveId());

		Archive merged = merge.merge(SCOPE, null).archive();
		assertEquals(Map.of("a.pdf", "A2"), extract(merged));
		DeletionPlan plan = deletion.prepare(SCOPE, null).orElseThrow();
		assertTrue(plan.verified(), plan.verificationProblems().toString());
		assertTrue(plan.lostContent().isEmpty());
		deletion.execute(SCOPE, plan);
		assertEquals(merged.id(), failures.findOpenByScopeKey(USER).getFirst().archiveId(),
				"deleting the archives re-points the report at the merged full");

		drive.unblock("b");
		SyncResult third = incremental.synchronize(ACCESS, SCOPE, null);

		assertNotNull(third.archive());
		assertEquals(Map.of("content/b", "B1"), extract(third.archive()));
		assertTrue(third.failures().isEmpty());
		assertTrue(failures.findOpen().isEmpty());
		assertEquals(1, count("download_failures WHERE open = 0 AND resolved_at IS NOT NULL"));
		Archive mergedAgain = merge.merge(SCOPE, null).archive();
		assertEquals(Map.of("a.pdf", "A2", "b.pdf", "B1"), extract(mergedAgain));
		assertEquals(extract(full.synchronize(ACCESS, SCOPE, null).archive()), extract(mergedAgain));
	}

	@Test
	void aNewRevisionThatCannotBeDownloadedKeepsTheOlderCaptureThroughMergeAndDeletion() throws Exception {
		drive.create("b", "b.pdf", "", PDF, "B1");
		full.synchronize(ACCESS, SCOPE, null);
		drive.edit("b", "B2");
		drive.block("b");

		SyncResult skipped = incremental.synchronize(ACCESS, SCOPE, null);

		assertEquals(1, skipped.failures().size());
		assertTrue(extract(skipped.archive()).isEmpty(), "the delta holds the metadata change but no bytes");
		Archive merged = merge.merge(SCOPE, null).archive();
		assertEquals(Map.of("b.pdf", "B1"), extract(merged), "the merge folds the last captured bytes");
		DeletionPlan plan = deletion.prepare(SCOPE, null).orElseThrow();
		assertTrue(plan.verified(), plan.verificationProblems().toString());
		assertTrue(plan.lostContent().isEmpty());
		deletion.execute(SCOPE, plan);
		assertEquals(Map.of("b.pdf", "B1"), extract(merged));
		assertEquals(0, count("file_captures WHERE archive_id NOT IN (SELECT id FROM archives)"));

		drive.unblock("b");
		SyncResult retried = incremental.synchronize(ACCESS, SCOPE, null);

		assertEquals(Map.of("content/b", "B2"), extract(retried.archive()));
		assertTrue(failures.findOpen().isEmpty());
		assertEquals(Map.of("b.pdf", "B2"), extract(merge.merge(SCOPE, null).archive()));
	}

	@Test
	void aRetryThatFailsAgainPublishesNoArchiveAndAFileDeletedMeanwhileClosesItsFailure() throws Exception {
		drive.create("a", "a.pdf", "", PDF, "A1");
		drive.create("b", "b.pdf", "", PDF, "B1");
		drive.block("b");
		full.synchronize(ACCESS, SCOPE, null);
		int archivesBefore = count("archives");

		SyncResult nothingNew = incremental.synchronize(ACCESS, SCOPE, null);

		assertNull(nothingNew.archive());
		assertEquals(archivesBefore, count("archives"));
		assertEquals(1, failures.findOpen().size());

		drive.delete("b");
		SyncResult afterDelete = incremental.synchronize(ACCESS, SCOPE, null);

		assertNotNull(afterDelete.archive());
		assertTrue(failures.findOpen().isEmpty());
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
