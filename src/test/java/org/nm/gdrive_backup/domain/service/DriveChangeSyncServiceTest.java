package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.nm.gdrive_backup.domain.model.PersonalDriveContent;
import org.nm.gdrive_backup.domain.model.FileCapture;
import org.nm.gdrive_backup.domain.model.DriveScopeType;
import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestFile;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.BackupStopMode;
import org.nm.gdrive_backup.domain.model.DriveChange;
import org.nm.gdrive_backup.domain.model.DriveChangePage;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.FileEvent;
import org.nm.gdrive_backup.domain.model.PendingCommit;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StaleDrivePageTokenException;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.model.SyncResult;
import org.nm.gdrive_backup.domain.model.SyncState;
import org.nm.gdrive_backup.domain.port.out.ArchivePort;
import org.nm.gdrive_backup.domain.port.out.DriveChangePort;
import org.nm.gdrive_backup.domain.port.out.DriveContentPort;
import org.nm.gdrive_backup.domain.port.out.FileMetadataPort;
import org.nm.gdrive_backup.domain.port.out.SyncStatePort;

class DriveChangeSyncServiceTest {

	private static final ServiceAccountAccess ACCESS = new ServiceAccountAccess(
			UUID.randomUUID(), "user@example.com", Instant.now().plusSeconds(3600), Set.of("drive.readonly"));
	private static final DriveScope SCOPE = DriveScope.personal("user@example.com");

	private final List<String> log = new ArrayList<>();
	private final DriveChangePort changePort = mock(DriveChangePort.class);
	private final SyncStatePort statePort = mock(SyncStatePort.class);
	private final FileMetadataPort metadataPort = mock(FileMetadataPort.class);
	private final DriveContentPort contentPort = mock(DriveContentPort.class);
	private final ArchivePort archivePort = mock(ArchivePort.class);
	private final RecordingArchiveSessionPort sessions = new RecordingArchiveSessionPort(log);
	private final RecordingSyncCommitPort commits = new RecordingSyncCommitPort(log);
	private final BackupCancellation cancellation = new BackupCancellation();
	private final BackupProgressTracker progressTracker = mock(BackupProgressTracker.class);
	private final DriveChangeSyncService service = new DriveChangeSyncService(changePort, statePort, metadataPort,
			new FileContentStreamingService(contentPort), sessions, new ArchiveRunPlanner(archivePort), commits,
			progressTracker, cancellation, () -> 1,
				() -> PersonalDriveContent.OWNED_ONLY);

	private final DriveChangeSyncService parallelService = new DriveChangeSyncService(changePort, statePort,
			metadataPort, new FileContentStreamingService(contentPort), sessions, new ArchiveRunPlanner(archivePort),
			commits, progressTracker, cancellation, () -> 4,
				() -> PersonalDriveContent.OWNED_ONLY);

	/** Stubs a feed that adds {@code count} new PDFs (file-0 ...) in one page. */
	private void stubNewFiles(int count) {
		baseline("old-token");
		List<DriveChange> changes = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			changes.add(new DriveChange("file-" + i, false, new StoredFile("file-" + i, "user@example.com",
					"F" + i + ".pdf", "", null, "application/pdf", false, "revision-" + i, null)));
		}
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY))
				.thenReturn(new DriveChangePage(changes, null, "new-token"));
	}

	private void baseline(String token) {
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.of(new SyncState("user@example.com", token)));
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of(
				new Archive(7L, "user@example.com", DriveScopeType.PERSONAL, 1, null, ArchiveMode.FULL, RevisionMode.LATEST_ONLY,
						Instant.now(), "archives/x.zip", null, null, false)));
	}

	private void knownFile(String fileId) {
		when(metadataPort.findByFileId(fileId)).thenReturn(Optional.of(new StoredFile(fileId, "user@example.com",
				fileId + ".pdf", "", null, "application/pdf", false, "revision-0", 1L)));
	}

	@Test
	void aRemovalOfAFileTheScopeNeverHeldIsIgnored() {
		baseline("old-token");
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("stranger", true, null)), null, "new-token"));

		SyncResult result = service.synchronize(ACCESS, SCOPE, null);

		assertNull(result.archive());
		assertTrue(commits.commits.getFirst().events().isEmpty());
	}

	@Test
	void drainsPagesAndCommitsOnceWithTheNewTokenAndNoPerPageCheckpoint() {
		baseline("old-token");
		knownFile("file-1");
		knownFile("file-2");
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-1", true, null)), "next-token", null));
		when(changePort.listChanges(ACCESS, SCOPE, "next-token", PersonalDriveContent.OWNED_ONLY))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-2", true, null)), null, "new-token"));

		SyncResult result = service.synchronize(ACCESS, SCOPE, null);

		assertEquals(2, result.changeCount());
		assertEquals("new-token", result.pageToken());
		verify(statePort, never()).save(any());
		assertEquals(1, commits.commits.size());
		PendingCommit commit = commits.commits.getFirst();
		assertEquals(new SyncState("user@example.com", "new-token"), commit.newSyncState());
		assertEquals(List.of("file-1", "file-2"), commit.events().stream().map(FileEvent::fileId).toList());
		assertTrue(commit.events().stream().allMatch(event -> event.eventType().equals("delete")));
		assertEquals(List.of("open", "publish", "commit"), log);
	}

	@Test
	void chainsTheDeltaOntoTheLatestArchiveAndRecordsTheTokenRange() {
		baseline("old-token");
		knownFile("file-1");
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-1", true, null)), null, "new-token"));

		SyncResult result = service.synchronize(ACCESS, SCOPE, null);

		Archive archive = commits.commits.getFirst().archiveOrNull();
		assertEquals(ArchiveMode.INCREMENTAL, archive.mode());
		assertEquals(2, archive.sequenceNumber());
		assertEquals(7L, archive.baseArchiveId());
		assertEquals("old-token", archive.fromPageToken());
		assertEquals("new-token", archive.toPageToken());
		assertEquals("archives/My Drive (user@example.com)/archive-0002-incremental.zip", sessions.openedPaths.getFirst());
		assertEquals(100L, result.archive().id());
		assertEquals("old-token", sessions.publishedManifest.fromPageToken());
		assertEquals("delete", sessions.publishedManifest.events().getFirst().eventType());
	}

	@Test
	void establishesAStartTokenForAnUninitializedScope() {
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.empty());
		when(changePort.getStartPageToken(ACCESS, SCOPE)).thenReturn("start-token");
		when(changePort.listChanges(ACCESS, SCOPE, "start-token", PersonalDriveContent.OWNED_ONLY))
				.thenReturn(new DriveChangePage(List.of(), null, "new-token"));

		SyncResult result = service.synchronize(ACCESS, SCOPE, null);

		assertEquals("new-token", result.pageToken());
	}

	@Test
	void anEmptyRunAdvancesOnlyTheCursorAndOpensNoArchive() {
		baseline("old-token");
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY))
				.thenReturn(new DriveChangePage(List.of(), null, "new-token"));

		SyncResult result = service.synchronize(ACCESS, SCOPE, null);

		assertNull(result.archive());
		assertFalse(result.cancelled());
		assertTrue(sessions.openedPaths.isEmpty());
		PendingCommit commit = commits.commits.getFirst();
		assertNull(commit.archiveOrNull());
		assertEquals(new SyncState("user@example.com", "new-token"), commit.newSyncState());
	}

	@Test
	void recordsMetadataDifferencesAsEventsCarriedInTheArchive() {
		baseline("old-token");
		StoredFile previous = new StoredFile("file-1", "user@example.com", "Old", "parent-a", null, "application/pdf",
				false, "revision-1", 5L);
		StoredFile current = new StoredFile("file-1", "user@example.com", "New", "parent-b", null, "application/pdf",
				true, "revision-1", null);
		when(metadataPort.findByFileId("file-1")).thenReturn(Optional.of(previous));
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-1", false, current)), null, "new-token"));

		service.synchronize(ACCESS, SCOPE, null);

		PendingCommit commit = commits.commits.getFirst();
		assertEquals(List.of("rename", "move", "trash"), commit.events().stream().map(FileEvent::eventType).toList());
		assertEquals(List.of("rename", "move", "trash"),
				sessions.publishedManifest.events().stream().map(event -> event.eventType()).toList());
		assertTrue(commit.captures().isEmpty());
		assertTrue(sessions.entries.isEmpty());
		// the previous capture stays the file's current version; only a commit of a new capture moves it
		assertEquals(5L, commit.files().getFirst().currentVersionId());
	}

	@Test
	void streamsChangedContentIntoAnIdKeyedEntryAndLeavesLinkingItToTheCommit() throws Exception {
		baseline("old-token");
		StoredFile previous = new StoredFile("file-1", "user@example.com", "Report.pdf", "", null, "application/pdf",
				false, "revision-1", 5L);
		StoredFile current = new StoredFile("file-1", "user@example.com", "Report.pdf", "", null, "application/pdf",
				false, "revision-2", null);
		when(metadataPort.findByFileId("file-1")).thenReturn(Optional.of(previous));
		when(contentPort.download(ACCESS, "file-1")).thenReturn(new ByteArrayInputStream("new-bytes".getBytes()));
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-1", false, current)), null, "new-token"));

		service.synchronize(ACCESS, SCOPE, null);

		assertEquals("new-bytes", new String(sessions.entries.get("content/file-1")));
		PendingCommit commit = commits.commits.getFirst();
		assertEquals(1, commit.captures().size());
		assertEquals("content/file-1", commit.captures().getFirst().entryName());
		assertEquals("revision-2", commit.captures().getFirst().revisionId());
		assertEquals("content/file-1", sessions.publishedManifest.files().getFirst().entry());
	}

	@Test
	void aFileChangedTwiceInOneRunIsStreamedOnceAndDiffedAgainstItsBufferedState() throws Exception {
		baseline("old-token");
		StoredFile previous = new StoredFile("file-1", "user@example.com", "A", "", null, "application/pdf", false,
				"revision-1", 5L);
		when(metadataPort.findByFileId("file-1")).thenReturn(Optional.of(previous));
		when(contentPort.download(ACCESS, "file-1")).thenReturn(new ByteArrayInputStream(new byte[] { 1 }));
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY)).thenReturn(new DriveChangePage(List.of(new DriveChange(
				"file-1", false, new StoredFile("file-1", "user@example.com", "B", "", null, "application/pdf", false,
						"revision-2", null))), "next-token", null));
		when(changePort.listChanges(ACCESS, SCOPE, "next-token", PersonalDriveContent.OWNED_ONLY)).thenReturn(new DriveChangePage(List.of(new DriveChange(
				"file-1", false, new StoredFile("file-1", "user@example.com", "C", "", null, "application/pdf", false,
						"revision-2", null))), null, "new-token"));

		service.synchronize(ACCESS, SCOPE, null);

		verify(contentPort, times(1)).download(ACCESS, "file-1");
		PendingCommit commit = commits.commits.getFirst();
		assertEquals(1, commit.captures().size());
		assertEquals(List.of("rename", "content", "rename"), commit.events().stream().map(FileEvent::eventType).toList());
		assertEquals("B", commit.events().get(2).oldValue());
		assertEquals("C", commit.events().get(2).newValue());
		assertEquals(1, commit.files().size());
		assertEquals("C", commit.files().getFirst().name());
	}

	@Test
	void aFileRemovedLaterInTheRunIsNoLongerDownloaded() throws Exception {
		baseline("old-token");
		when(metadataPort.findByFileId("file-1")).thenReturn(Optional.empty());
		StoredFile added = new StoredFile("file-1", "user@example.com", "A.pdf", "", null, "application/pdf", false,
				"revision-1", null);
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY)).thenReturn(new DriveChangePage(
				List.of(new DriveChange("file-1", false, added), new DriveChange("file-1", true, null)), null,
				"new-token"));

		service.synchronize(ACCESS, SCOPE, null);

		verify(contentPort, never()).download(any(), any());
		assertEquals("delete", commits.commits.getFirst().events().getLast().eventType());
	}

	@Test
	void reportsEachProcessedChangeThenPackaging() {
		baseline("old-token");
		StoredFile current = new StoredFile("file-1", "user@example.com", "Report", "", null, "application/pdf", false,
				null, null);
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY)).thenReturn(new DriveChangePage(
				List.of(new DriveChange("file-1", false, current), new DriveChange("file-2", true, null)), null,
				"new-token"));

		service.synchronize(ACCESS, SCOPE, null);

		verify(progressTracker).itemProcessed("Report");
		verify(progressTracker).itemProcessed("file-2");
		verify(progressTracker).packaging();
	}

	@Test
	void anImmediateStopBetweenPagesCommitsNothing() {
		baseline("old-token");
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY)).thenAnswer(invocation -> {
			cancellation.requestStop(BackupStopMode.IMMEDIATE);
			return new DriveChangePage(List.of(new DriveChange("file-1", true, null)), "next-token", null);
		});

		SyncResult result = service.synchronize(ACCESS, SCOPE, null);

		assertTrue(result.cancelled());
		assertTrue(commits.commits.isEmpty());
		assertTrue(sessions.openedPaths.isEmpty());
		verify(changePort, times(1)).listChanges(any(), any(), any(), any());
	}

	@Test
	void aStopWhileStreamingDiscardsTheDeltaAndCommitsNothing() throws Exception {
		baseline("old-token");
		StoredFile current = new StoredFile("file-1", "user@example.com", "Report.pdf", "", null, "application/pdf",
				false, "revision-2", null);
		when(metadataPort.findByFileId("file-1")).thenReturn(Optional.empty());
		when(contentPort.download(ACCESS, "file-1")).thenAnswer(invocation -> {
			cancellation.requestStop(BackupStopMode.IMMEDIATE);
			return new ByteArrayInputStream(new byte[] { 1 });
		});
		StoredFile second = new StoredFile("file-2", "user@example.com", "Other.pdf", "", null, "application/pdf",
				false, "revision-1", null);
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY)).thenReturn(new DriveChangePage(
				List.of(new DriveChange("file-1", false, current), new DriveChange("file-2", false, second)), null,
				"new-token"));

		SyncResult result = service.synchronize(ACCESS, SCOPE, null);

		assertTrue(result.cancelled());
		assertEquals(List.of("open", "discard"), log);
		assertTrue(commits.commits.isEmpty());
		verify(contentPort, never()).download(ACCESS, "file-2");
	}

	@Test
	void aStaleTokenFailsBeforeAnythingIsStagedOrCommitted() {
		baseline("expired-token");
		when(changePort.listChanges(ACCESS, SCOPE, "expired-token", PersonalDriveContent.OWNED_ONLY))
				.thenThrow(new StaleDrivePageTokenException("expired", null));

		assertThrows(StaleDrivePageTokenException.class, () -> service.synchronize(ACCESS, SCOPE, null));

		assertTrue(log.isEmpty());
	}

	@Test
	void aDownloadFailureDiscardsTheDeltaAndCommitsNothing() throws Exception {
		baseline("old-token");
		StoredFile current = new StoredFile("file-1", "user@example.com", "Report.pdf", "", null, "application/pdf",
				false, "revision-2", null);
		when(metadataPort.findByFileId("file-1")).thenReturn(Optional.empty());
		when(contentPort.download(ACCESS, "file-1")).thenThrow(new IOException("connection reset"));
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-1", false, current)), null, "new-token"));

		assertThrows(IllegalStateException.class, () -> service.synchronize(ACCESS, SCOPE, null));

		assertEquals(List.of("open", "discard"), log);
		assertTrue(commits.commits.isEmpty());
	}

	@Test
	void aNewVersionOfANativeFileIsReExportedAndAFormIsRecordedWithoutContent() throws Exception {
		baseline("old-token");
		StoredFile previousDoc = new StoredFile("doc-1", "user@example.com", "Notes", "", null,
				"application/vnd.google-apps.document", false, "v2", 5L);
		StoredFile currentDoc = new StoredFile("doc-1", "user@example.com", "Notes", "", null,
				"application/vnd.google-apps.document", false, "v3", null);
		StoredFile form = new StoredFile("form-1", "user@example.com", "Survey", "", null,
				"application/vnd.google-apps.form", false, "v1", null);
		when(metadataPort.findByFileId("doc-1")).thenReturn(Optional.of(previousDoc));
		when(metadataPort.findByFileId("form-1")).thenReturn(Optional.empty());
		when(contentPort.export(ACCESS, "doc-1",
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
				.thenReturn(new ByteArrayInputStream(new byte[] { 1 }));
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY)).thenReturn(new DriveChangePage(
				List.of(new DriveChange("doc-1", false, currentDoc), new DriveChange("form-1", false, form)), null,
				"new-token"));

		service.synchronize(ACCESS, SCOPE, null);

		assertEquals(Set.of("content/doc-1"), sessions.entries.keySet());
		assertEquals("v3", commits.commits.getFirst().captures().getFirst().revisionId());
		assertEquals(2, commits.commits.getFirst().files().size());
	}

	@Test
	void aRenameOnlyChangeIsListedWithItsNewMetadataAndNoEntry() {
		baseline("old-token");
		StoredFile previous = new StoredFile("file-1", "user@example.com", "Old.pdf", "folder-1", null, "application/pdf",
				false, "revision-1", 5L);
		StoredFile current = new StoredFile("file-1", "user@example.com", "New.pdf", "folder-1", null, "application/pdf",
				false, "revision-1", null);
		when(metadataPort.findByFileId("file-1")).thenReturn(Optional.of(previous));
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-1", false, current)), null, "new-token"));

		service.synchronize(ACCESS, SCOPE, null);

		var record = sessions.publishedManifest.files().getFirst();
		assertEquals("file-1", record.fileId());
		assertEquals("New.pdf", record.name());
		assertEquals(List.of("folder-1"), record.parents());
		assertNull(record.entry());
		assertNull(record.revisionId());
		assertEquals(1, sessions.publishedManifest.files().size());
		assertEquals(Integer.valueOf(1), sessions.publishedManifest.baseSequenceNumber());
	}

	@Test
	void aContentChangeRecordsTheEntryRevisionSizeAndExportFormat() throws Exception {
		baseline("old-token");
		StoredFile previous = new StoredFile("doc-1", "user@example.com", "Notes", "", null,
				"application/vnd.google-apps.document", false, "v2", 5L);
		StoredFile current = new StoredFile("doc-1", "user@example.com", "Notes", "", null,
				"application/vnd.google-apps.document", false, "v3", null);
		when(metadataPort.findByFileId("doc-1")).thenReturn(Optional.of(previous));
		when(contentPort.export(ACCESS, "doc-1",
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
				.thenReturn(new ByteArrayInputStream(new byte[] { 1, 2, 3 }));
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("doc-1", false, current)), null, "new-token"));

		service.synchronize(ACCESS, SCOPE, null);

		var record = sessions.publishedManifest.files().getFirst();
		assertEquals("content/doc-1", record.entry());
		assertEquals("v3", record.revisionId());
		assertEquals(3L, record.sizeBytes());
		assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
				record.exportMimeType());
	}

	@Test
	void aRemovedFileGetsARemovedRecordAndATouchedThenRemovedFileCollapsesToOne() {
		baseline("old-token");
		StoredFile touched = new StoredFile("file-1", "user@example.com", "A.pdf", "", null, "application/pdf", false,
				null, null);
		when(metadataPort.findByFileId("file-1")).thenReturn(Optional.empty());
		knownFile("file-2");
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY)).thenReturn(new DriveChangePage(
				List.of(new DriveChange("file-1", false, touched), new DriveChange("file-1", true, null),
						new DriveChange("file-2", true, null)), null, "new-token"));

		service.synchronize(ACCESS, SCOPE, null);

		var records = sessions.publishedManifest.files();
		assertEquals(List.of("file-1", "file-2"), records.stream().map(r -> r.fileId()).toList());
		assertTrue(records.stream().allMatch(r -> r.removed()));
		assertNull(records.getFirst().name());
	}

	@Test
	void aBrandNewFolderIsArchivedEvenThoughItHasNoEventAndNoContent() {
		baseline("old-token");
		StoredFile folder = new StoredFile("folder-9", "user@example.com", "Projects", "", null,
				"application/vnd.google-apps.folder", false, null, null);
		when(metadataPort.findByFileId("folder-9")).thenReturn(Optional.empty());
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("folder-9", false, folder)), null, "new-token"));

		SyncResult result = service.synchronize(ACCESS, SCOPE, null);

		assertEquals(100L, result.archive().id());
		assertEquals("Projects", sessions.publishedManifest.files().getFirst().name());
		assertTrue(sessions.publishedManifest.events().isEmpty());
		assertTrue(commits.commits.getFirst().captures().isEmpty());
	}

	@Test
	void aKnownFileThatLeavesTheScopeIsRecordedAsRemoved() throws Exception {
		baseline("old-token");
		StoredFile known = new StoredFile("file-1", "user@example.com", "A.pdf", "", null, "application/pdf", false,
				"revision-1", 5L);
		when(metadataPort.findByFileId("file-1")).thenReturn(Optional.of(known));
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY))
				.thenReturn(new DriveChangePage(List.of(DriveChange.outOfScope("file-1")), null, "new-token"));

		SyncResult result = service.synchronize(ACCESS, SCOPE, null);

		assertEquals(100L, result.archive().id());
		assertEquals(List.of(ManifestFile.removed("file-1")), sessions.publishedManifest.files());
		assertEquals("delete", sessions.publishedManifest.events().getFirst().eventType());
		verify(contentPort, never()).download(any(), any());
	}

	@Test
	void anUnknownFileOutsideTheScopeIsIgnored() {
		baseline("old-token");
		when(metadataPort.findByFileId("shared-1")).thenReturn(Optional.empty());
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY))
				.thenReturn(new DriveChangePage(List.of(DriveChange.outOfScope("shared-1")), null, "new-token"));

		SyncResult result = service.synchronize(ACCESS, SCOPE, null);

		assertNull(result.archive());
		assertTrue(sessions.openedPaths.isEmpty());
		PendingCommit commit = commits.commits.getFirst();
		assertTrue(commit.files().isEmpty());
		assertTrue(commit.events().isEmpty());
		assertEquals(new SyncState("user@example.com", "new-token"), commit.newSyncState());
	}

	@Test
	void readsThePersonalDriveContentWhenTheRunStartsAndPassesItToTheFeed() {
		baseline("old-token");
		AtomicReference<PersonalDriveContent> content = new AtomicReference<>(PersonalDriveContent.ALL_ACCESSIBLE);
		DriveChangeSyncService adjustable = new DriveChangeSyncService(changePort, statePort, metadataPort,
				new FileContentStreamingService(contentPort), sessions, new ArchiveRunPlanner(archivePort), commits,
				progressTracker, cancellation, () -> 1, content::get);
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.ALL_ACCESSIBLE))
				.thenAnswer(invocation -> {
					content.set(PersonalDriveContent.OWNED_ONLY);
					return new DriveChangePage(List.of(), "next-token", null);
				});
		when(changePort.listChanges(ACCESS, SCOPE, "next-token", PersonalDriveContent.ALL_ACCESSIBLE))
				.thenReturn(new DriveChangePage(List.of(), null, "new-token"));

		SyncResult result = adjustable.synchronize(ACCESS, SCOPE, null);

		assertEquals("new-token", result.pageToken());
	}

	@Test
	void anUnchangedKnownFileTouchedByTheFeedDoesNotProduceAnArchive() {
		baseline("old-token");
		StoredFile same = new StoredFile("file-1", "user@example.com", "A.pdf", "", null, "application/pdf", false,
				"revision-1", 5L);
		when(metadataPort.findByFileId("file-1")).thenReturn(Optional.of(same));
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-1", false, same)), null, "new-token"));

		SyncResult result = service.synchronize(ACCESS, SCOPE, null);

		assertNull(result.archive());
		assertTrue(sessions.openedPaths.isEmpty());
	}

	@Test
	void downloadsChangedFilesInParallelAndKeepsTheManifestInTheOrderTheFeedReportedThem() throws Exception {
		int count = 8;
		stubNewFiles(count);
		CountDownLatch firstWindowStarted = new CountDownLatch(4);
		for (int i = 0; i < count; i++) {
			int index = i;
			when(contentPort.download(ACCESS, "file-" + i)).thenAnswer(invocation -> {
				firstWindowStarted.countDown();
				// Four downloads must be in flight together, which a one-at-a-time run can never reach.
				assertTrue(firstWindowStarted.await(5, TimeUnit.SECONDS), "downloads did not overlap");
				// Earlier files take longer, so they finish after the later ones.
				Thread.sleep((count - index) * 10L);
				return new ByteArrayInputStream(new byte[] { (byte) index });
			});
		}

		SyncResult result = parallelService.synchronize(ACCESS, SCOPE, null);

		assertFalse(result.cancelled());
		List<String> expectedEntries = IntStream.range(0, count).mapToObj(i -> "content/file-" + i).toList();
		// Each file is written as it completes, so the entries are in completion order; the manifest keeps the feed order.
		assertEquals(new java.util.HashSet<>(expectedEntries), sessions.entries.keySet());
		assertEquals(new java.util.HashSet<>(expectedEntries),
				commits.commits.getFirst().captures().stream().map(FileCapture::entryName).collect(java.util.stream.Collectors.toSet()));
		assertEquals(expectedEntries, sessions.publishedManifest.files().stream().map(file -> file.entry()).toList());
		for (int i = 0; i < count; i++) {
			assertEquals(i, sessions.entries.get("content/file-" + i)[0]);
		}
		assertEquals(List.of("open", "publish", "commit"), log);
		assertTrue(sessions.allStagedReleased());
	}

	@Test
	void aVeryLargeChangedFileDoesNotStopTheOtherDownloadsFromStarting() throws Exception {
		int count = 20;
		stubNewFiles(count);
		// Twelve other downloads, three times the concurrency, must start while the big file is still going.
		CountDownLatch othersStarted = new CountDownLatch(12);
		for (int i = 0; i < count; i++) {
			if (i == 0) {
				when(contentPort.download(ACCESS, "file-0")).thenAnswer(invocation -> {
					if (!othersStarted.await(5, TimeUnit.SECONDS)) {
						throw new IllegalStateException("the other downloads waited for the big file");
					}
					return new ByteArrayInputStream(new byte[] { 1 });
				});
			} else {
				when(contentPort.download(ACCESS, "file-" + i)).thenAnswer(invocation -> {
					othersStarted.countDown();
					return new ByteArrayInputStream(new byte[] { 1 });
				});
			}
		}

		SyncResult result = parallelService.synchronize(ACCESS, SCOPE, null);

		assertFalse(result.cancelled());
		assertEquals(count, sessions.entries.size());
		assertEquals(List.of("open", "publish", "commit"), log);
	}

	@Test
	void readsTheDownloadConcurrencyAtTheStartOfEachRun() throws Exception {
		AtomicInteger concurrency = new AtomicInteger(1);
		DriveChangeSyncService adjustable = new DriveChangeSyncService(changePort, statePort, metadataPort,
				new FileContentStreamingService(contentPort), sessions, new ArchiveRunPlanner(archivePort), commits,
				progressTracker, cancellation, concurrency::get,
				() -> PersonalDriveContent.OWNED_ONLY);
		AtomicInteger inFlight = new AtomicInteger();
		AtomicInteger mostInFlight = new AtomicInteger();
		stubNewFiles(6);
		for (int i = 0; i < 6; i++) {
			when(contentPort.download(ACCESS, "file-" + i)).thenAnswer(invocation -> {
				mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
				Thread.sleep(40);
				inFlight.decrementAndGet();
				return new ByteArrayInputStream(new byte[] { 1 });
			});
		}

		adjustable.synchronize(ACCESS, SCOPE, null);
		assertEquals(1, mostInFlight.get(), "a concurrency of 1 downloads one file at a time");

		concurrency.set(4);
		mostInFlight.set(0);
		adjustable.synchronize(ACCESS, SCOPE, null);
		assertTrue(mostInFlight.get() > 1, "the new value applies to the next run");
		assertTrue(mostInFlight.get() <= 4, "but never exceeds it, saw " + mostInFlight.get());
	}

	@Test
	void listsEachDownloadWithItsPathBuiltFromFoldersKnownFromEarlierRuns() throws Exception {
		baseline("old-token");
		StoredFile docs = new StoredFile("folder-1", "user@example.com", "Docs", "root-id", null,
				"application/vnd.google-apps.folder", false, null, 3L);
		StoredFile reports = new StoredFile("folder-2", "user@example.com", "Reports", "folder-1", null,
				"application/vnd.google-apps.folder", false, null, 4L);
		when(metadataPort.findByFileId("folder-2")).thenReturn(Optional.of(reports));
		when(metadataPort.findByFileId("folder-1")).thenReturn(Optional.of(docs));
		StoredFile report = new StoredFile("file-1", "user@example.com", "Q3.pdf", "folder-2", null, "application/pdf",
				false, "revision-1", null);
		when(contentPort.download(ACCESS, "file-1")).thenReturn(new ByteArrayInputStream(new byte[] { 1 }));
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-1", false, report)), null, "new-token"));

		parallelService.synchronize(ACCESS, SCOPE, null);

		InOrder order = inOrder(progressTracker);
		order.verify(progressTracker).downloadStarted("file-1", "Q3.pdf", "My Drive/Docs/Reports/Q3.pdf", null);
		order.verify(progressTracker).downloadFinished("file-1");
	}

	@Test
	void foldersChangedInTheSameRunAreUsedForThePathEvenIfTheFeedListsThemAfterTheFile() throws Exception {
		baseline("old-token");
		StoredFile newFolder = new StoredFile("folder-9", "user@example.com", "New folder", "", null,
				"application/vnd.google-apps.folder", false, null, null);
		StoredFile report = new StoredFile("file-1", "user@example.com", "A.pdf", "folder-9", null, "application/pdf",
				false, "revision-1", null);
		when(contentPort.download(ACCESS, "file-1")).thenReturn(new ByteArrayInputStream(new byte[] { 1 }));
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY)).thenReturn(new DriveChangePage(
				List.of(new DriveChange("file-1", false, report), new DriveChange("folder-9", false, newFolder)), null,
				"new-token"));

		parallelService.synchronize(ACCESS, SCOPE, null);

		verify(progressTracker).downloadStarted("file-1", "A.pdf", "My Drive/New folder/A.pdf", null);
	}

	@Test
	void aSharedDriveChangePathStartsWithTheDriveName() throws Exception {
		DriveScope shared = DriveScope.sharedDrive("drive-1");
		when(statePort.findByScopeKey("drive-1")).thenReturn(Optional.of(new SyncState("drive-1", "old-token")));
		when(archivePort.findByScopeKey("drive-1")).thenReturn(List.of(
				new Archive(7L, "drive-1", DriveScopeType.SHARED_DRIVE, 1, null, ArchiveMode.FULL,
						RevisionMode.LATEST_ONLY, Instant.now(), "archives/x.zip", null, null, false)));
		StoredFile report = new StoredFile("file-1", "drive-1", "A.pdf", "", "drive-1", "application/pdf", false,
				"revision-1", null);
		when(contentPort.download(ACCESS, "file-1")).thenReturn(new ByteArrayInputStream(new byte[] { 1 }));
		when(changePort.listChanges(ACCESS, shared, "old-token", PersonalDriveContent.OWNED_ONLY))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-1", false, report)), null, "new-token"));

		parallelService.synchronize(ACCESS, shared, "Finance");

		verify(progressTracker).downloadStarted("file-1", "A.pdf", "Finance/A.pdf", null);
	}

	@Test
	void aChangedFilesSizeFromDriveSurvivesUntilItsDownloadStarts() throws Exception {
		baseline("old-token");
		StoredFile previous = new StoredFile("file-1", "user@example.com", "Big.pdf", "", null, "application/pdf",
				false, "revision-1", 5L);
		StoredFile current = new StoredFile("file-1", "user@example.com", "Big.pdf", "", null, "application/pdf",
				false, "revision-2", null, 9L);
		when(metadataPort.findByFileId("file-1")).thenReturn(Optional.of(previous));
		when(contentPort.download(ACCESS, "file-1")).thenReturn(new ByteArrayInputStream(new byte[9]));
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-1", false, current)), null, "new-token"));

		parallelService.synchronize(ACCESS, SCOPE, null);

		InOrder order = inOrder(progressTracker);
		order.verify(progressTracker).downloadStarted("file-1", "Big.pdf", "My Drive/Big.pdf", 9L);
		order.verify(progressTracker, atLeastOnce()).downloadProgressed("file-1", 9L);
		order.verify(progressTracker).downloadFinished("file-1");
	}

	@Test
	void anAbortedChangeDownloadLeavesTheListInsteadOfBeingShownAsDone() throws Exception {
		stubNewFiles(1);
		when(contentPort.download(ACCESS, "file-0")).thenThrow(new IOException("connection reset"));

		assertThrows(IllegalStateException.class, () -> parallelService.synchronize(ACCESS, SCOPE, null));

		verify(progressTracker).downloadStarted("file-0", "F0.pdf", "My Drive/F0.pdf", null);
		verify(progressTracker).downloadAborted("file-0");
		verify(progressTracker, never()).downloadFinished(any());
	}

	@Test
	void changedContentIsStoredOrCompressedPerFileType() throws Exception {
		baseline("old-token");
		StoredFile pdf = new StoredFile("pdf-1", "user@example.com", "A.pdf", "", null, "application/pdf", false, "r1", null);
		StoredFile text = new StoredFile("txt-1", "user@example.com", "B.txt", "", null, "text/plain", false, "r1", null);
		when(contentPort.download(ACCESS, "pdf-1")).thenReturn(new ByteArrayInputStream(new byte[] { 1 }));
		when(contentPort.download(ACCESS, "txt-1")).thenReturn(new ByteArrayInputStream(new byte[] { 2 }));
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY)).thenReturn(new DriveChangePage(
				List.of(new DriveChange("pdf-1", false, pdf), new DriveChange("txt-1", false, text)), null, "new-token"));

		parallelService.synchronize(ACCESS, SCOPE, null);

		assertEquals(false, sessions.compressedByEntry.get("content/pdf-1"));
		assertEquals(true, sessions.compressedByEntry.get("content/txt-1"));
	}

	@Test
	void aDownloadFailureAmongParallelDownloadsDiscardsTheDeltaAndCommitsNothing() throws Exception {
		stubNewFiles(6);
		for (int i = 0; i < 6; i++) {
			if (i == 2) {
				when(contentPort.download(ACCESS, "file-2")).thenAnswer(invocation -> {
					Thread.sleep(100);
					throw new IOException("connection reset");
				});
			} else {
				when(contentPort.download(ACCESS, "file-" + i)).thenReturn(new ByteArrayInputStream(new byte[] { 1 }));
			}
		}

		assertThrows(IllegalStateException.class, () -> parallelService.synchronize(ACCESS, SCOPE, null));

		assertEquals(List.of("open", "discard"), log);
		assertTrue(commits.commits.isEmpty());
		assertTrue(sessions.allStagedReleased(), "content fetched but never written is released");
	}

	@Test
	void aStopWhileDownloadingInParallelDiscardsTheDeltaAndCommitsNothing() throws Exception {
		stubNewFiles(6);
		when(contentPort.download(ACCESS, "file-0")).thenAnswer(invocation -> {
			cancellation.requestStop(BackupStopMode.IMMEDIATE);
			return new ByteArrayInputStream(new byte[] { 1 });
		});
		for (int i = 1; i < 6; i++) {
			when(contentPort.download(ACCESS, "file-" + i)).thenReturn(new ByteArrayInputStream(new byte[] { 1 }));
		}

		SyncResult result = parallelService.synchronize(ACCESS, SCOPE, null);

		assertTrue(result.cancelled());
		assertNull(result.archive());
		assertEquals(List.of("open", "discard"), log);
		assertTrue(commits.commits.isEmpty());
	}

	@Test
	void anUntrashedFileIsReCapturedEvenAtAnUnchangedRevision() throws Exception {
		baseline("old-token");
		StoredFile trashed = new StoredFile("file-1", "user@example.com", "A.pdf", "", null, "application/pdf", true,
				"revision-1", 5L);
		StoredFile untrashed = new StoredFile("file-1", "user@example.com", "A.pdf", "", null, "application/pdf", false,
				"revision-1", null);
		when(metadataPort.findByFileId("file-1")).thenReturn(Optional.of(trashed));
		when(contentPort.download(ACCESS, "file-1")).thenReturn(new ByteArrayInputStream("bytes".getBytes()));
		when(changePort.listChanges(ACCESS, SCOPE, "old-token", PersonalDriveContent.OWNED_ONLY))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-1", false, untrashed)), null, "new-token"));

		service.synchronize(ACCESS, SCOPE, null);

		assertEquals("bytes", new String(sessions.entries.get("content/file-1")));
		assertEquals(List.of("untrash"), commits.commits.getFirst().events().stream().map(FileEvent::eventType).toList());
	}
}
