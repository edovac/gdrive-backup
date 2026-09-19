package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.BackupStopMode;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.InitialSyncResult;
import org.nm.gdrive_backup.domain.model.PendingCommit;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.model.SyncState;
import org.nm.gdrive_backup.domain.port.out.ArchivePort;
import org.nm.gdrive_backup.domain.port.out.DriveChangePort;
import org.nm.gdrive_backup.domain.port.out.DriveContentPort;
import org.nm.gdrive_backup.domain.port.out.DriveFileListingPort;

class InitialDriveSyncServiceTest {

	private static final ServiceAccountAccess ACCESS = new ServiceAccountAccess(
			UUID.randomUUID(), "user@example.com", Instant.now().plusSeconds(3600), Set.of("drive.readonly"));
	private static final DriveScope SCOPE = DriveScope.personal("user@example.com");
	private static final String FOLDER = "application/vnd.google-apps.folder";

	private final List<String> log = new ArrayList<>();
	private final DriveFileListingPort listingPort = mock(DriveFileListingPort.class);
	private final DriveChangePort changePort = mock(DriveChangePort.class);
	private final DriveContentPort contentPort = mock(DriveContentPort.class);
	private final ArchivePort archivePort = mock(ArchivePort.class);
	private final RecordingArchiveSessionPort sessions = new RecordingArchiveSessionPort(log);
	private final RecordingSyncCommitPort commits = new RecordingSyncCommitPort(log);
	private final BackupCancellation cancellation = new BackupCancellation();
	private final BackupProgressTracker progressTracker = mock(BackupProgressTracker.class);
	private final InitialDriveSyncService service = new InitialDriveSyncService(listingPort, changePort,
			new FileContentStreamingService(contentPort), sessions, new ArchiveRunPlanner(archivePort), commits,
			progressTracker, cancellation);

	@Test
	void streamsEveryFileIntoADriveShapedArchiveAndCommitsAfterPublishing() throws Exception {
		StoredFile folder = file("folder-1", "Docs", "", FOLDER, null);
		StoredFile report = file("file-1", "Report.pdf", "folder-1", "application/pdf", "revision-1");
		stubDrive("baseline-token", folder, report);
		stubDownload("file-1", "report-bytes");

		InitialSyncResult result = service.synchronize(ACCESS, SCOPE, null);

		assertEquals(List.of("open", "publish", "commit"), log);
		assertEquals("archives/My Drive (user@example.com)/archive-0001-full.zip", sessions.openedPaths.getFirst());
		assertEquals(Set.of("Docs/Report.pdf"), sessions.entries.keySet());
		assertEquals("report-bytes", new String(sessions.entries.get("Docs/Report.pdf")));

		PendingCommit commit = commits.commits.getFirst();
		assertEquals(ArchiveMode.FULL, commit.archiveOrNull().mode());
		assertNull(commit.archiveOrNull().baseArchiveId());
		assertEquals(List.of(folder, report), commit.files());
		assertEquals(1, commit.captures().size());
		assertEquals("Docs/Report.pdf", commit.captures().getFirst().entryName());
		assertTrue(commit.events().isEmpty());
		assertEquals(new SyncState("user@example.com", "baseline-token"), commit.newSyncState());

		assertEquals(2, result.fileCount());
		assertEquals("baseline-token", result.pageToken());
		assertNotNull(result.archive());
		assertFalse(result.cancelled());
		assertEquals(ArchiveMode.FULL, sessions.publishedManifest.mode());
		assertEquals(RevisionMode.LATEST_ONLY, sessions.publishedManifest.revisionMode());
		assertEquals("Docs/Report.pdf", sessions.publishedManifest.files().getFirst().path());
		assertNull(sessions.publishedManifest.toPageToken());
	}

	@Test
	void fetchesTheBaselineTokenBeforeListingSoChangesDuringTheRunAreReplayed() {
		stubDrive("baseline-token");

		service.synchronize(ACCESS, SCOPE, null);

		InOrder order = inOrder(changePort, listingPort);
		order.verify(changePort).getStartPageToken(ACCESS, SCOPE);
		order.verify(listingPort).listAllFiles(ACCESS, SCOPE);
	}

	@Test
	void redownloadsEveryFileEvenWhenNothingChangedSinceTheLastArchive() throws Exception {
		StoredFile report = file("file-1", "Report.pdf", "", "application/pdf", "revision-1");
		stubDrive("token", report);
		stubDownload("file-1", "bytes");
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of(previousArchive(1, 10L)));

		service.synchronize(ACCESS, SCOPE, null);

		verify(contentPort).download(ACCESS, "file-1");
		assertEquals("archives/My Drive (user@example.com)/archive-0002-full.zip", sessions.openedPaths.getFirst());
		assertNull(commits.commits.getFirst().archiveOrNull().baseArchiveId());
	}

	@Test
	void recordsButDoesNotDownloadFoldersTrashedFilesOrFilesWithoutARevision() throws Exception {
		StoredFile folder = file("folder-1", "Docs", "", FOLDER, null);
		StoredFile trashed = new StoredFile("file-1", "user@example.com", "Old.pdf", "", null, "application/pdf", true,
				"revision-1", null);
		StoredFile noRevision = file("file-2", "Pending.pdf", "", "application/pdf", null);
		StoredFile live = file("file-3", "Live.pdf", "", "application/pdf", "revision-1");
		stubDrive("token", folder, trashed, noRevision, live);
		stubDownload("file-3", "bytes");

		service.synchronize(ACCESS, SCOPE, null);

		verify(contentPort, never()).download(ACCESS, "file-1");
		verify(contentPort, never()).download(ACCESS, "file-2");
		assertEquals(Set.of("Live.pdf"), sessions.entries.keySet());
		assertEquals(List.of(folder, trashed, noRevision, live), commits.commits.getFirst().files());
	}

	@Test
	void exportsNativeFilesWithTheirExtensionSoADocAndASheetWithOneNameDoNotCollide() throws Exception {
		StoredFile doc = file("file-1", "Budget", "", "application/vnd.google-apps.document", "revision-1");
		StoredFile sheet = file("file-2", "Budget", "", "application/vnd.google-apps.spreadsheet", "revision-1");
		stubDrive("token", doc, sheet);
		when(contentPort.export(ACCESS, "file-1",
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
				.thenReturn(new ByteArrayInputStream(new byte[] { 1 }));
		when(contentPort.export(ACCESS, "file-2",
				"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
				.thenReturn(new ByteArrayInputStream(new byte[] { 2 }));

		service.synchronize(ACCESS, SCOPE, null);

		assertEquals(Set.of("Budget.docx", "Budget.xlsx"), sessions.entries.keySet());
	}

	@Test
	void reportsEnumerationTotalThenPerFileProgressThenPackaging() throws Exception {
		stubDrive("token", file("file-1", "A.pdf", "", "application/pdf", "r1"),
				file("file-2", "B.pdf", "", "application/pdf", "r1"));
		stubDownload("file-1", "a");
		stubDownload("file-2", "b");

		service.synchronize(ACCESS, SCOPE, null);

		InOrder order = inOrder(progressTracker);
		order.verify(progressTracker).enumerating();
		order.verify(progressTracker).enumerated(2);
		order.verify(progressTracker).itemProcessed("A.pdf");
		order.verify(progressTracker).itemProcessed("B.pdf");
		order.verify(progressTracker).packaging();
	}

	@Test
	void anImmediateStopDiscardsTheStagedArchiveAndCommitsNothing() throws Exception {
		stubDrive("token", file("file-1", "A.pdf", "", "application/pdf", "r1"),
				file("file-2", "B.pdf", "", "application/pdf", "r1"));
		when(contentPort.download(ACCESS, "file-1")).thenAnswer(invocation -> {
			cancellation.requestStop(BackupStopMode.IMMEDIATE);
			return new ByteArrayInputStream(new byte[] { 1 });
		});

		InitialSyncResult result = service.synchronize(ACCESS, SCOPE, null);

		assertTrue(result.cancelled());
		assertNull(result.archive());
		assertNull(result.pageToken());
		assertEquals(List.of("open", "discard"), log);
		assertTrue(commits.commits.isEmpty());
		verify(contentPort, never()).download(ACCESS, "file-2");
	}

	@Test
	void aDownloadFailureDiscardsTheStagedArchiveAndCommitsNothing() throws Exception {
		stubDrive("token", file("file-1", "A.pdf", "", "application/pdf", "r1"));
		when(contentPort.download(ACCESS, "file-1")).thenThrow(new IOException("connection reset"));

		assertThrows(IllegalStateException.class, () -> service.synchronize(ACCESS, SCOPE, null));

		assertEquals(List.of("open", "discard"), log);
		assertTrue(commits.commits.isEmpty());
	}

	@Test
	void carriesTheDisplayNameIntoTheSharedDriveArchiveFolder() {
		DriveScope shared = DriveScope.sharedDrive("drive-1");
		when(changePort.getStartPageToken(ACCESS, shared)).thenReturn("token");
		when(listingPort.listAllFiles(ACCESS, shared)).thenReturn(List.of());

		service.synchronize(ACCESS, shared, "Finance");

		assertEquals("archives/Finance (drive-1)/archive-0001-full.zip", sessions.openedPaths.getFirst());
	}

	private void stubDrive(String token, StoredFile... files) {
		when(changePort.getStartPageToken(ACCESS, SCOPE)).thenReturn(token);
		when(listingPort.listAllFiles(ACCESS, SCOPE)).thenReturn(List.of(files));
	}

	private void stubDownload(String fileId, String content) throws IOException {
		when(contentPort.download(ACCESS, fileId)).thenReturn(new ByteArrayInputStream(content.getBytes()));
	}

	private static StoredFile file(String id, String name, String parents, String mimeType, String revision) {
		return new StoredFile(id, "user@example.com", name, parents, null, mimeType, false, revision, null);
	}

	private static Archive previousArchive(int sequenceNumber, long id) {
		return new Archive(id, "user@example.com", sequenceNumber, null, ArchiveMode.FULL, RevisionMode.LATEST_ONLY,
				Instant.now(), "archives/x.zip", null, null, false);
	}
}
