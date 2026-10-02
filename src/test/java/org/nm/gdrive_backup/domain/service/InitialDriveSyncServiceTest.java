package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.nm.gdrive_backup.domain.model.DriveScopeType;
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
			progressTracker, cancellation, 1);
	private final InitialDriveSyncService parallelService = new InitialDriveSyncService(listingPort, changePort,
			new FileContentStreamingService(contentPort), sessions, new ArchiveRunPlanner(archivePort), commits,
			progressTracker, cancellation, 4);

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
		assertEquals(DriveScope.personal("user@example.com"), sessions.publishedManifest.scope());
		assertNull(sessions.publishedManifest.baseSequenceNumber());
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
	void backsUpNativeFilesButNotFormsOrShortcuts() throws Exception {
		StoredFile doc = file("file-1", "Notes", "", "application/vnd.google-apps.document", "v3");
		StoredFile form = file("file-2", "Survey", "", "application/vnd.google-apps.form", "v1");
		StoredFile shortcut = file("file-3", "Link", "", "application/vnd.google-apps.shortcut", "v1");
		stubDrive("token", doc, form, shortcut);
		when(contentPort.export(ACCESS, "file-1",
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
				.thenReturn(new ByteArrayInputStream(new byte[] { 1 }));

		service.synchronize(ACCESS, SCOPE, null);

		assertEquals(Set.of("Notes.docx"), sessions.entries.keySet());
		assertEquals("v3", commits.commits.getFirst().captures().getFirst().revisionId());
		assertEquals(3, commits.commits.getFirst().files().size());
	}

	@Test
	void theManifestListsEveryFileAndFolderButOnlyStreamedOnesCarryAnEntry() throws Exception {
		StoredFile folder = file("folder-1", "Docs", "", FOLDER, null);
		StoredFile report = new StoredFile("file-1", "user@example.com", "Report.pdf", "folder-1,folder-2", "drive-9",
				"application/pdf", false, "revision-1", null);
		StoredFile form = file("file-2", "Survey", "folder-1", "application/vnd.google-apps.form", "v1");
		StoredFile doc = file("file-3", "Notes", "folder-1", "application/vnd.google-apps.document", "v3");
		stubDrive("token", folder, report, form, doc);
		stubDownload("file-1", "bytes");
		when(contentPort.export(ACCESS, "file-3",
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
				.thenThrow(new org.nm.gdrive_backup.domain.model.DriveExportLimitException("too large", null));
		when(contentPort.export(ACCESS, "file-3", "application/pdf"))
				.thenReturn(new ByteArrayInputStream(new byte[] { 1, 2 }));

		service.synchronize(ACCESS, SCOPE, null);

		var records = sessions.publishedManifest.files();
		assertEquals(List.of("folder-1", "file-1", "file-2", "file-3"), records.stream().map(r -> r.fileId()).toList());
		var folderRecord = records.get(0);
		assertNull(folderRecord.entry());
		assertNull(folderRecord.revisionId());
		assertEquals(FOLDER, folderRecord.mimeType());
		var reportRecord = records.get(1);
		assertEquals("Docs/Report.pdf", reportRecord.entry());
		assertEquals("revision-1", reportRecord.revisionId());
		assertEquals(5L, reportRecord.sizeBytes());
		assertEquals(List.of("folder-1", "folder-2"), reportRecord.parents());
		assertEquals("drive-9", reportRecord.driveId());
		assertNull(reportRecord.exportMimeType());
		var formRecord = records.get(2);
		assertNull(formRecord.entry());
		assertEquals("application/vnd.google-apps.form", formRecord.mimeType());
		var docRecord = records.get(3);
		assertEquals("Docs/Notes.pdf", docRecord.entry());
		assertEquals("application/pdf", docRecord.exportMimeType());
		assertEquals("v3", docRecord.revisionId());
		assertEquals("Notes", docRecord.name());
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
	void downloadsInParallelButWritesTheArchiveInListingOrderAndReportsEveryFileOnce() throws Exception {
		int count = 8;
		List<StoredFile> files = new ArrayList<>();
		CountDownLatch firstWindowStarted = new CountDownLatch(4);
		for (int i = 0; i < count; i++) {
			int index = i;
			files.add(file("file-" + i, "F" + i + ".pdf", "", "application/pdf", "revision-" + i));
			when(contentPort.download(ACCESS, "file-" + i)).thenAnswer(invocation -> {
				firstWindowStarted.countDown();
				// Four downloads must be in flight together, which a one-at-a-time run can never reach.
				assertTrue(firstWindowStarted.await(5, TimeUnit.SECONDS), "downloads did not overlap");
				// Earlier files take longer, so they finish after the later ones.
				Thread.sleep((count - index) * 10L);
				return new ByteArrayInputStream(new byte[] { (byte) index });
			});
		}
		stubDrive("token", files.toArray(StoredFile[]::new));

		InitialSyncResult result = parallelService.synchronize(ACCESS, SCOPE, null);

		assertFalse(result.cancelled());
		assertEquals(count, result.fileCount());
		List<String> expectedEntries = files.stream().map(StoredFile::name).toList();
		assertEquals(expectedEntries, List.copyOf(sessions.entries.keySet()));
		for (int i = 0; i < count; i++) {
			assertEquals(i, sessions.entries.get("F" + i + ".pdf")[0]);
		}
		assertEquals(expectedEntries,
				commits.commits.getFirst().captures().stream().map(capture -> capture.entryName()).toList());
		assertEquals(files.stream().map(StoredFile::fileId).toList(),
				sessions.publishedManifest.files().stream().map(record -> record.fileId()).toList());
		// Progress follows download completion, which is not list order, so only the count and the end are fixed.
		for (StoredFile file : files) {
			verify(progressTracker, times(1)).itemProcessed(file.name());
		}
		InOrder order = inOrder(progressTracker);
		order.verify(progressTracker, times(count)).itemProcessed(anyString());
		order.verify(progressTracker).packaging();
		assertEquals(List.of("open", "publish", "commit"), log);
		assertTrue(sessions.allStagedReleased());
	}

	@Test
	void aSlowDownloadDoesNotHoldBackTheProgressOfTheFilesQueuedBehindIt() throws Exception {
		List<StoredFile> files = new ArrayList<>();
		CountDownLatch othersReported = new CountDownLatch(4);
		doAnswer(invocation -> {
			if (!"F0.pdf".equals(invocation.getArgument(0))) {
				othersReported.countDown();
			}
			return null;
		}).when(progressTracker).itemProcessed(anyString());
		for (int i = 0; i < 5; i++) {
			files.add(file("file-" + i, "F" + i + ".pdf", "", "application/pdf", "r1"));
			if (i == 0) {
				when(contentPort.download(ACCESS, "file-0")).thenAnswer(invocation -> {
					// The first file only finishes once the four behind it were counted. Counting at hand-over
					// instead would leave them waiting on this file, so this would time out.
					if (!othersReported.await(5, TimeUnit.SECONDS)) {
						throw new IllegalStateException("progress was held back by the slow file");
					}
					return new ByteArrayInputStream(new byte[] { 1 });
				});
			} else {
				stubDownload("file-" + i, "bytes");
			}
		}
		stubDrive("token", files.toArray(StoredFile[]::new));

		InitialSyncResult result = parallelService.synchronize(ACCESS, SCOPE, null);

		assertFalse(result.cancelled());
		assertEquals(List.of("F0.pdf", "F1.pdf", "F2.pdf", "F3.pdf", "F4.pdf"), List.copyOf(sessions.entries.keySet()));
		verify(progressTracker, times(5)).itemProcessed(anyString());
	}

	@Test
	void filesWithNoContentToDownloadAreReportedOnceEachAlongsideTheDownloadedOnes() throws Exception {
		StoredFile folder = file("folder-1", "Docs", "", FOLDER, null);
		StoredFile trashed = new StoredFile("file-1", "user@example.com", "Old.pdf", "", null, "application/pdf", true,
				"revision-1", null);
		StoredFile form = file("file-2", "Survey", "folder-1", "application/vnd.google-apps.form", "v1");
		StoredFile first = file("file-3", "A.pdf", "folder-1", "application/pdf", "r1");
		StoredFile second = file("file-4", "B.pdf", "folder-1", "application/pdf", "r1");
		stubDrive("token", folder, trashed, form, first, second);
		stubDownload("file-3", "a");
		stubDownload("file-4", "b");

		parallelService.synchronize(ACCESS, SCOPE, null);

		for (String name : List.of("Docs", "Old.pdf", "Survey", "A.pdf", "B.pdf")) {
			verify(progressTracker, times(1)).itemProcessed(name);
		}
		verify(progressTracker, times(5)).itemProcessed(anyString());
	}

	@Test
	void parallelDownloadsStillSkipFoldersAndFilesWithoutContentAndKeepEveryFileInTheListing() throws Exception {
		StoredFile folder = file("folder-1", "Docs", "", FOLDER, null);
		StoredFile first = file("file-1", "A.pdf", "folder-1", "application/pdf", "r1");
		StoredFile form = file("file-2", "Survey", "folder-1", "application/vnd.google-apps.form", "v1");
		StoredFile second = file("file-3", "B.pdf", "folder-1", "application/pdf", "r1");
		stubDrive("token", folder, first, form, second);
		stubDownload("file-1", "a");
		stubDownload("file-3", "b");

		parallelService.synchronize(ACCESS, SCOPE, null);

		assertEquals(List.of("Docs/A.pdf", "Docs/B.pdf"), List.copyOf(sessions.entries.keySet()));
		assertEquals(List.of(folder, first, form, second), commits.commits.getFirst().files());
		verify(contentPort, never()).download(ACCESS, "file-2");
	}

	@Test
	void contentIsStoredOrCompressedPerFileType() throws Exception {
		stubDrive("token", file("file-1", "Report.pdf", "", "application/pdf", "r1"),
				file("file-2", "notes.txt", "", "text/plain", "r1"),
				file("file-3", "Budget", "", "application/vnd.google-apps.spreadsheet", "v3"));
		stubDownload("file-1", "pdf");
		stubDownload("file-2", "text");
		when(contentPort.export(ACCESS, "file-3",
				"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
				.thenReturn(new ByteArrayInputStream(new byte[] { 1 }));

		service.synchronize(ACCESS, SCOPE, null);

		assertEquals(false, sessions.compressedByEntry.get("Report.pdf"));
		assertEquals(true, sessions.compressedByEntry.get("notes.txt"));
		assertEquals(false, sessions.compressedByEntry.get("Budget.xlsx"));
	}

	@Test
	void aPdfFallbackDuringParallelDownloadsIsRenamedAgainstTheNamesAlreadyInTheArchive() throws Exception {
		StoredFile existingPdf = file("file-1", "Report.pdf", "", "application/pdf", "r1");
		StoredFile doc = file("file-2", "Report", "", "application/vnd.google-apps.document", "v3");
		stubDrive("token", existingPdf, doc);
		stubDownload("file-1", "pdf");
		when(contentPort.export(ACCESS, "file-2",
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
				.thenThrow(new org.nm.gdrive_backup.domain.model.DriveExportLimitException("too large", null));
		when(contentPort.export(ACCESS, "file-2", "application/pdf"))
				.thenReturn(new ByteArrayInputStream(new byte[] { 1 }));

		parallelService.synchronize(ACCESS, SCOPE, null);

		assertEquals(List.of("Report.pdf", "Report (2).pdf"), List.copyOf(sessions.entries.keySet()));
		assertEquals("Report (2).pdf", sessions.publishedManifest.files().get(1).entry());
	}

	@Test
	void aDownloadFailureAmongParallelDownloadsDiscardsTheStagedArchiveAndCommitsNothing() throws Exception {
		List<StoredFile> files = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			files.add(file("file-" + i, "F" + i + ".pdf", "", "application/pdf", "r1"));
			if (i == 2) {
				when(contentPort.download(ACCESS, "file-2")).thenAnswer(invocation -> {
					Thread.sleep(100);
					throw new IOException("connection reset");
				});
			} else {
				stubDownload("file-" + i, "bytes");
			}
		}
		stubDrive("token", files.toArray(StoredFile[]::new));

		assertThrows(IllegalStateException.class, () -> parallelService.synchronize(ACCESS, SCOPE, null));

		assertEquals(List.of("open", "discard"), log);
		assertTrue(commits.commits.isEmpty());
		assertTrue(sessions.allStagedReleased(), "content fetched but never written is released");
	}

	@Test
	void anImmediateStopDuringParallelDownloadsDiscardsTheStagedArchiveAndCommitsNothing() throws Exception {
		List<StoredFile> files = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			files.add(file("file-" + i, "F" + i + ".pdf", "", "application/pdf", "r1"));
			if (i == 0) {
				when(contentPort.download(ACCESS, "file-0")).thenAnswer(invocation -> {
					cancellation.requestStop(BackupStopMode.IMMEDIATE);
					return new ByteArrayInputStream(new byte[] { 1 });
				});
			} else {
				stubDownload("file-" + i, "bytes");
			}
		}
		stubDrive("token", files.toArray(StoredFile[]::new));

		InitialSyncResult result = parallelService.synchronize(ACCESS, SCOPE, null);

		assertTrue(result.cancelled());
		assertNull(result.archive());
		assertNull(result.pageToken());
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
		return new Archive(id, "user@example.com", DriveScopeType.PERSONAL, sequenceNumber, null, ArchiveMode.FULL, RevisionMode.LATEST_ONLY,
				Instant.now(), "archives/x.zip", null, null, false);
	}
}
