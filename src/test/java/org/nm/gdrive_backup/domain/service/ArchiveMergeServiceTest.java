package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.DriveScopeType;
import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveChainException;
import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestFile;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.PendingCommit;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.port.out.ArchivePort;

class ArchiveMergeServiceTest {

	private static final DriveScope SCOPE = DriveScope.personal("user@example.com");
	private static final String FOLDER = "application/vnd.google-apps.folder";
	private static final String PDF = "application/pdf";
	private static final String DOC = "application/vnd.google-apps.document";
	private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
	private static final String P1 = "archives/My Drive (user@example.com)/archive-0001-full.zip";
	private static final String P2 = "archives/My Drive (user@example.com)/archive-0002-incremental.zip";
	private static final String P3 = "archives/My Drive (user@example.com)/archive-0003-incremental.zip";

	private final List<String> log = new ArrayList<>();
	private final ArchivePort archivePort = mock(ArchivePort.class);
	private final FakeArchiveReaderPort readers = new FakeArchiveReaderPort();
	private final RecordingArchiveSessionPort sessions = new RecordingArchiveSessionPort(log);
	private final RecordingSyncCommitPort commits = new RecordingSyncCommitPort(log);
	private final BackupActivity activity = new BackupActivity();
	private final BackupProgressTracker progressTracker = mock(BackupProgressTracker.class);
	private final BackupCancellation cancellation = new BackupCancellation();
	private final ArchiveMergeService service = new ArchiveMergeService(archivePort, readers, sessions,
			new ArchiveRunPlanner(archivePort), commits, activity, progressTracker, cancellation);

	private Archive full;
	private Archive first;
	private Archive second;

	@BeforeEach
	void buildAChain() {
		full = row(1, 1, null, ArchiveMode.FULL, P1, null);
		first = row(2, 2, 1L, ArchiveMode.INCREMENTAL, P2, "t2");
		second = row(3, 3, 2L, ArchiveMode.INCREMENTAL, P3, "t3");
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of(full, first, second));

		// 0001 full: folder Docs; a.pdf and b.pdf in it; gone.pdf at the root
		readers.add(P1, manifest(1, ArchiveMode.FULL, null, null,
				file("folder-1", "Docs", FOLDER, List.of(), null, null, null, false),
				file("a", "a.pdf", PDF, List.of("folder-1"), "Docs/a.pdf", "r1", 2L, false),
				file("b", "b.pdf", PDF, List.of("folder-1"), "Docs/b.pdf", "r1", 2L, false),
				file("gone", "gone.pdf", PDF, List.of(), "gone.pdf", "r1", 2L, false)),
				Map.of("Docs/a.pdf", "A1", "Docs/b.pdf", "B1", "gone.pdf", "G1"));
		// 0002: a updated, b renamed (metadata only), a native doc added
		readers.add(P2, manifest(2, ArchiveMode.INCREMENTAL, 1, "t2",
				file("a", "a.pdf", PDF, List.of("folder-1"), "content/a", "r2", 3L, false),
				file("b", "b-renamed.pdf", PDF, List.of("folder-1"), null, null, null, false),
				new ManifestFile("doc", false, "Notes", List.of(), null, DOC, false, "v3", "content/doc", 1L, DOCX)),
				Map.of("content/a", "A22", "content/doc", "N"));
		// 0003: gone removed, b moved to the root
		readers.add(P3, manifest(3, ArchiveMode.INCREMENTAL, 2, "t3",
				ManifestFile.removed("gone"),
				file("b", "b-renamed.pdf", PDF, List.of(), null, null, null, false)),
				Map.of());
	}

	@Test
	void foldsTheChainIntoAFlatTreeReadingOnlyArchives() {
		Archive merged = service.merge(SCOPE, null).archive();

		assertEquals(Set.of("Docs/a.pdf", "b-renamed.pdf", "Notes.docx"), sessions.entries.keySet());
		assertEquals("A22", new String(sessions.entries.get("Docs/a.pdf")));
		assertEquals("B1", new String(sessions.entries.get("b-renamed.pdf")));
		assertEquals("N", new String(sessions.entries.get("Notes.docx")));
		assertEquals(List.of("open", "publish", "commit"), log);
		assertEquals("archives/My Drive (user@example.com)/archive-0004-merged-full.zip", sessions.openedPaths.getFirst());
		assertEquals(100L, merged.id());
	}

	@Test
	void theMergedManifestDescribesTheMergedStateAndItsSources() {
		service.merge(SCOPE, null);

		ArchiveManifest manifest = sessions.publishedManifest;
		assertEquals(ArchiveMode.MERGED_FULL, manifest.mode());
		assertEquals(4, manifest.sequenceNumber());
		assertNull(manifest.baseSequenceNumber());
		assertNull(manifest.fromPageToken());
		assertEquals("t3", manifest.toPageToken());
		assertEquals(List.of(1, 2, 3), manifest.sourceArchives().stream().map(s -> s.sequenceNumber()).toList());
		assertEquals("archive-0002-incremental.zip", manifest.sourceArchives().get(1).fileName());
		assertTrue(manifest.events().isEmpty());

		Map<String, ManifestFile> byId = new java.util.HashMap<>();
		manifest.files().forEach(record -> byId.put(record.fileId(), record));
		assertEquals(Set.of("folder-1", "a", "b", "doc"), byId.keySet());
		assertNull(byId.get("folder-1").entry());
		assertEquals("Docs/a.pdf", byId.get("a").entry());
		assertEquals("r2", byId.get("a").revisionId());
		assertEquals(3L, byId.get("a").sizeBytes());
		assertEquals("b-renamed.pdf", byId.get("b").entry());
		assertEquals("b-renamed.pdf", byId.get("b").name());
		assertEquals(List.of(), byId.get("b").parents());
		assertEquals("Notes.docx", byId.get("doc").entry());
		assertEquals(DOCX, byId.get("doc").exportMimeType());
	}

	@Test
	void commitsTheSourceArchivesAndLeavesTheCursorAndFileTablesAlone() {
		service.merge(SCOPE, null);

		PendingCommit commit = commits.commits.getFirst();
		assertEquals(ArchiveMode.MERGED_FULL, commit.archiveOrNull().mode());
		assertNull(commit.archiveOrNull().baseArchiveId());
		assertEquals("t3", commit.archiveOrNull().toPageToken());
		assertEquals(List.of(1L, 2L, 3L), commit.sourceArchiveIds());
		assertNull(commit.newSyncState());
		assertTrue(commit.files().isEmpty());
		assertTrue(commit.events().isEmpty());
		assertTrue(commit.captures().isEmpty());
	}

	@Test
	void aTrashedFileIsKeptAsMetadataButLeftOutOfTheTree() {
		readers.add(P3, manifest(3, ArchiveMode.INCREMENTAL, 2, "t3",
				file("a", "a.pdf", PDF, List.of("folder-1"), null, null, null, true)), Map.of());

		service.merge(SCOPE, null);

		assertTrue(!sessions.entries.containsKey("Docs/a.pdf"));
		ManifestFile record = sessions.publishedManifest.files().stream().filter(f -> f.fileId().equals("a")).findFirst()
				.orElseThrow();
		assertTrue(record.trashed());
		assertNull(record.entry());
	}

	@Test
	void aBrokenLinkAbortsBeforeAnythingIsStaged() {
		Archive orphan = row(3, 3, 42L, ArchiveMode.INCREMENTAL, P3, "t3");
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of(full, first, orphan));

		assertThrows(ArchiveChainException.class, () -> service.merge(SCOPE, null));

		assertTrue(log.isEmpty());
	}

	@Test
	void aMissingArchiveFileAbortsBeforeAnythingIsStaged() {
		Archive lost = row(3, 3, 2L, ArchiveMode.INCREMENTAL, "archives/missing.zip", "t3");
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of(full, first, lost));

		ArchiveChainException exception = assertThrows(ArchiveChainException.class, () -> service.merge(SCOPE, null));

		assertTrue(exception.getMessage().contains("Archive 3"));
		assertTrue(log.isEmpty());
	}

	@Test
	void aManifestThatContradictsItsRecordAbortsTheMerge() {
		readers.add(P2, manifest(9, ArchiveMode.INCREMENTAL, 1, "t2"), Map.of());

		ArchiveChainException exception = assertThrows(ArchiveChainException.class, () -> service.merge(SCOPE, null));

		assertTrue(exception.getMessage().contains("Archive 2"));
		assertTrue(log.isEmpty());
	}

	@Test
	void anIncrementalThatChainsOntoTheWrongArchiveAbortsTheMerge() {
		readers.add(P3, manifest(3, ArchiveMode.INCREMENTAL, 1, "t3"), Map.of());

		assertThrows(ArchiveChainException.class, () -> service.merge(SCOPE, null));

		assertTrue(log.isEmpty());
	}

	@Test
	void aSizeThatDisagreesWithTheManifestDiscardsTheMergeAndCommitsNothing() {
		readers.add(P2, manifest(2, ArchiveMode.INCREMENTAL, 1, "t2",
				file("a", "a.pdf", PDF, List.of("folder-1"), "content/a", "r2", 99L, false)),
				Map.of("content/a", "A22"));

		assertThrows(ArchiveChainException.class, () -> service.merge(SCOPE, null));

		assertEquals(List.of("open", "discard"), log);
		assertTrue(commits.commits.isEmpty());
	}

	@Test
	void anEntryMissingFromItsArchiveDiscardsTheMergeAndCommitsNothing() {
		readers.add(P2, manifest(2, ArchiveMode.INCREMENTAL, 1, "t2",
				file("a", "a.pdf", PDF, List.of("folder-1"), "content/a", "r2", 3L, false)), Map.of());

		assertThrows(ArchiveChainException.class, () -> service.merge(SCOPE, null));

		assertEquals(List.of("open", "discard"), log);
		assertTrue(commits.commits.isEmpty());
	}

	@Test
	void reportsProgressAsAOneDriveJobInTheBackupOrder() {
		service.merge(SCOPE, "My Drive");

		var order = org.mockito.Mockito.inOrder(progressTracker);
		order.verify(progressTracker).jobStarted(List.of(new org.nm.gdrive_backup.domain.model.AvailableDrive(
				"user@example.com", "My Drive", false)));
		order.verify(progressTracker).driveStarted(new org.nm.gdrive_backup.domain.model.AvailableDrive(
				"user@example.com", "My Drive", false));
		order.verify(progressTracker).enumerating();
		order.verify(progressTracker).enumerated(4);
		order.verify(progressTracker).itemProcessed("Docs");
		order.verify(progressTracker).packaging();
		order.verify(progressTracker).driveCompleted();
		order.verify(progressTracker).jobFinished();
	}

	@Test
	void anImmediateStopDiscardsTheMergeAndCommitsNothing() {
		org.mockito.Mockito.doAnswer(invocation -> {
			cancellation.requestStop(org.nm.gdrive_backup.domain.model.BackupStopMode.IMMEDIATE);
			return null;
		}).when(progressTracker).itemProcessed("a.pdf");

		var result = service.merge(SCOPE, null);

		assertTrue(result.cancelled());
		assertNull(result.archive());
		assertEquals(List.of("open", "discard"), log);
		assertTrue(commits.commits.isEmpty());
		org.mockito.Mockito.verify(progressTracker).jobFinished();
	}

	@Test
	void aFailureStillFinishesTheProgressJob() {
		Archive lost = row(3, 3, 2L, ArchiveMode.INCREMENTAL, "archives/missing.zip", "t3");
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of(full, first, lost));

		assertThrows(ArchiveChainException.class, () -> service.merge(SCOPE, null));

		org.mockito.Mockito.verify(progressTracker).jobFinished();
	}

	@Test
	void refusesToRunWhileABackupIsRunning() {
		assertThrows(IllegalStateException.class, () -> activity.duringBackup(() -> service.merge(SCOPE, null)));

		assertTrue(log.isEmpty());
	}

	private static Archive row(long id, int sequenceNumber, Long baseId, ArchiveMode mode, String path, String toToken) {
		return new Archive(id, "user@example.com", DriveScopeType.PERSONAL, sequenceNumber, baseId, mode, RevisionMode.LATEST_ONLY, Instant.now(),
				path, null, toToken, false);
	}

	private static ArchiveManifest manifest(int sequenceNumber, ArchiveMode mode, Integer base, String toToken,
			ManifestFile... files) {
		return new ArchiveManifest(SCOPE, mode, RevisionMode.LATEST_ONLY, sequenceNumber, base, Instant.now(), null,
				toToken, List.of(), List.of(files), List.of());
	}

	private static ManifestFile file(String id, String name, String mime, List<String> parents, String entry,
			String revision, Long size, boolean trashed) {
		return new ManifestFile(id, false, name, parents, null, mime, trashed, revision, entry, size, null);
	}
}
