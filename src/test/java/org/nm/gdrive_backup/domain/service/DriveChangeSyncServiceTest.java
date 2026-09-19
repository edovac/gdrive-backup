package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.Archive;
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
			progressTracker, cancellation);

	private void baseline(String token) {
		when(statePort.findByScopeKey("user@example.com")).thenReturn(Optional.of(new SyncState("user@example.com", token)));
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of(
				new Archive(7L, "user@example.com", 1, null, ArchiveMode.FULL, RevisionMode.LATEST_ONLY,
						Instant.now(), "archives/x.zip", null, null, false)));
	}

	@Test
	void drainsPagesAndCommitsOnceWithTheNewTokenAndNoPerPageCheckpoint() {
		baseline("old-token");
		when(changePort.listChanges(ACCESS, SCOPE, "old-token"))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-1", true, null)), "next-token", null));
		when(changePort.listChanges(ACCESS, SCOPE, "next-token"))
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
		when(changePort.listChanges(ACCESS, SCOPE, "old-token"))
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
		when(changePort.listChanges(ACCESS, SCOPE, "start-token"))
				.thenReturn(new DriveChangePage(List.of(), null, "new-token"));

		SyncResult result = service.synchronize(ACCESS, SCOPE, null);

		assertEquals("new-token", result.pageToken());
	}

	@Test
	void anEmptyRunAdvancesOnlyTheCursorAndOpensNoArchive() {
		baseline("old-token");
		when(changePort.listChanges(ACCESS, SCOPE, "old-token"))
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
		when(changePort.listChanges(ACCESS, SCOPE, "old-token"))
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
		when(changePort.listChanges(ACCESS, SCOPE, "old-token"))
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
		when(changePort.listChanges(ACCESS, SCOPE, "old-token")).thenReturn(new DriveChangePage(List.of(new DriveChange(
				"file-1", false, new StoredFile("file-1", "user@example.com", "B", "", null, "application/pdf", false,
						"revision-2", null))), "next-token", null));
		when(changePort.listChanges(ACCESS, SCOPE, "next-token")).thenReturn(new DriveChangePage(List.of(new DriveChange(
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
		when(changePort.listChanges(ACCESS, SCOPE, "old-token")).thenReturn(new DriveChangePage(
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
		when(changePort.listChanges(ACCESS, SCOPE, "old-token")).thenReturn(new DriveChangePage(
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
		when(changePort.listChanges(ACCESS, SCOPE, "old-token")).thenAnswer(invocation -> {
			cancellation.requestStop(BackupStopMode.IMMEDIATE);
			return new DriveChangePage(List.of(new DriveChange("file-1", true, null)), "next-token", null);
		});

		SyncResult result = service.synchronize(ACCESS, SCOPE, null);

		assertTrue(result.cancelled());
		assertTrue(commits.commits.isEmpty());
		assertTrue(sessions.openedPaths.isEmpty());
		verify(changePort, times(1)).listChanges(any(), any(), any());
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
		when(changePort.listChanges(ACCESS, SCOPE, "old-token")).thenReturn(new DriveChangePage(
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
		when(changePort.listChanges(ACCESS, SCOPE, "expired-token"))
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
		when(changePort.listChanges(ACCESS, SCOPE, "old-token"))
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
		when(changePort.listChanges(ACCESS, SCOPE, "old-token")).thenReturn(new DriveChangePage(
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
		when(changePort.listChanges(ACCESS, SCOPE, "old-token"))
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
		when(changePort.listChanges(ACCESS, SCOPE, "old-token"))
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
		when(changePort.listChanges(ACCESS, SCOPE, "old-token")).thenReturn(new DriveChangePage(
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
		when(changePort.listChanges(ACCESS, SCOPE, "old-token"))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("folder-9", false, folder)), null, "new-token"));

		SyncResult result = service.synchronize(ACCESS, SCOPE, null);

		assertEquals(100L, result.archive().id());
		assertEquals("Projects", sessions.publishedManifest.files().getFirst().name());
		assertTrue(sessions.publishedManifest.events().isEmpty());
		assertTrue(commits.commits.getFirst().captures().isEmpty());
	}

	@Test
	void anUnchangedKnownFileTouchedByTheFeedDoesNotProduceAnArchive() {
		baseline("old-token");
		StoredFile same = new StoredFile("file-1", "user@example.com", "A.pdf", "", null, "application/pdf", false,
				"revision-1", 5L);
		when(metadataPort.findByFileId("file-1")).thenReturn(Optional.of(same));
		when(changePort.listChanges(ACCESS, SCOPE, "old-token"))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-1", false, same)), null, "new-token"));

		SyncResult result = service.synchronize(ACCESS, SCOPE, null);

		assertNull(result.archive());
		assertTrue(sessions.openedPaths.isEmpty());
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
		when(changePort.listChanges(ACCESS, SCOPE, "old-token"))
				.thenReturn(new DriveChangePage(List.of(new DriveChange("file-1", false, untrashed)), null, "new-token"));

		service.synchronize(ACCESS, SCOPE, null);

		assertEquals("bytes", new String(sessions.entries.get("content/file-1")));
		assertEquals(List.of("untrash"), commits.commits.getFirst().events().stream().map(FileEvent::eventType).toList());
	}
}
