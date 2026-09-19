package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.nm.gdrive_backup.domain.model.DriveChange;
import org.nm.gdrive_backup.domain.model.DriveChangePage;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.port.out.DriveChangePort;
import org.nm.gdrive_backup.domain.port.out.DriveContentPort;
import org.nm.gdrive_backup.domain.port.out.DriveFileListingPort;
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

	private static final String USER = "user@example.com";
	private static final DriveScope SCOPE = DriveScope.personal(USER);
	private static final ServiceAccountAccess ACCESS = new ServiceAccountAccess(
			UUID.randomUUID(), USER, Instant.now().plusSeconds(3600), Set.of("drive.readonly"));
	private static final String FOLDER = "application/vnd.google-apps.folder";
	private static final String PDF = "application/pdf";
	private static final String DOC = "application/vnd.google-apps.document";

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
				BackupProgressTracker.NO_OP, new BackupCancellation());
		incremental = new DriveChangeSyncService(drive, new SqliteSyncStateAdapter(database),
				new SqliteFileMetadataAdapter(database), streaming, sessions, planner, commit,
				BackupProgressTracker.NO_OP, new BackupCancellation());
		merge = new ArchiveMergeService(archives, new LocalArchiveReaderAdapter(root), sessions, planner, commit,
				new BackupActivity());
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

		Archive merged = merge.merge(SCOPE, null);
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

		Archive merged = merge.merge(SCOPE, null);

		assertEquals(Map.of("Docs/a.pdf", "A2", "Docs/Notes.docx", "N2", "Archive/old.pdf", "O1",
				"New/new.pdf", "W2"), extract(merged));
	}

	@Test
	void incrementalBackupsContinueAfterAMergeAndCanBeMergedAgain() throws Exception {
		buildInitialDrive();
		changeTheDriveTwiceAndBackUpIncrementally();
		Archive merged = merge.merge(SCOPE, null);

		drive.edit("a", "A3");
		drive.create("extra", "extra.pdf", "folder-docs", PDF, "X1");
		Archive next = incremental.synchronize(ACCESS, SCOPE, null).archive();

		assertEquals(ArchiveMode.INCREMENTAL, next.mode());
		assertEquals(5, next.sequenceNumber());
		assertEquals(merged.id(), next.baseArchiveId());

		Archive mergedAgain = merge.merge(SCOPE, null);
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

		Archive merged = merge.merge(SCOPE, null);

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

	/** A minimal in-memory Drive: files with content and revisions, plus a change log addressed by position. */
	private static final class FakeDrive implements DriveFileListingPort, DriveChangePort, DriveContentPort {

		private final Map<String, Node> nodes = new LinkedHashMap<>();
		private final List<DriveChange> log = new ArrayList<>();

		void create(String id, String name, String parents, String mime, String content) {
			nodes.put(id, new Node(id, name, parents, mime, content));
			touch(id);
		}

		void edit(String id, String content) {
			Node node = nodes.get(id);
			node.content = content;
			node.revision++;
			touch(id);
		}

		void rename(String id, String name) {
			nodes.get(id).name = name;
			touch(id);
		}

		void move(String id, String parents) {
			nodes.get(id).parents = parents;
			touch(id);
		}

		void trash(String id) {
			nodes.get(id).trashed = true;
			touch(id);
		}

		void untrash(String id) {
			nodes.get(id).trashed = false;
			touch(id);
		}

		void delete(String id) {
			nodes.remove(id);
			log.add(new DriveChange(id, true, null));
		}

		private void touch(String id) {
			log.add(new DriveChange(id, false, nodes.get(id).toStoredFile()));
		}

		@Override
		public List<StoredFile> listAllFiles(ServiceAccountAccess access, DriveScope scope) {
			return nodes.values().stream().filter(node -> !node.trashed).map(Node::toStoredFile).toList();
		}

		@Override
		public String getStartPageToken(ServiceAccountAccess access, DriveScope scope) {
			return Integer.toString(log.size());
		}

		@Override
		public DriveChangePage listChanges(ServiceAccountAccess access, DriveScope scope, String pageToken) {
			int from = Integer.parseInt(pageToken);
			return new DriveChangePage(new ArrayList<>(log.subList(from, log.size())), null,
					Integer.toString(log.size()));
		}

		@Override
		public InputStream download(ServiceAccountAccess access, String fileId) {
			return new ByteArrayInputStream(nodes.get(fileId).content.getBytes(StandardCharsets.UTF_8));
		}

		@Override
		public InputStream export(ServiceAccountAccess access, String fileId, String exportMimeType) {
			return download(access, fileId);
		}

		private static final class Node {
			final String id;
			String name;
			String parents;
			final String mime;
			String content;
			boolean trashed;
			int revision = 1;

			Node(String id, String name, String parents, String mime, String content) {
				this.id = id;
				this.name = name;
				this.parents = parents;
				this.mime = mime;
				this.content = content;
			}

			StoredFile toStoredFile() {
				String head = mime.equals(FOLDER) ? null : mime.equals(DOC) ? "v" + revision : "rev-" + revision;
				return new StoredFile(id, USER, name, parents, null, mime, trashed, head, null);
			}
		}
	}
}
