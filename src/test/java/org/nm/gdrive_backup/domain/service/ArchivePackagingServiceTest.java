package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveEntry;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.FileCapture;
import org.nm.gdrive_backup.domain.model.FileEvent;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.model.SyncResult;
import org.nm.gdrive_backup.domain.port.out.ArchivePort;
import org.nm.gdrive_backup.domain.port.out.ArchiveWriterPort;
import org.nm.gdrive_backup.domain.port.out.FileCapturePort;
import org.nm.gdrive_backup.domain.port.out.FileMetadataPort;

class ArchivePackagingServiceTest {

	private static final DriveScope SCOPE = DriveScope.personal("user@example.com");
	private static final String FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";

	private final ArchivePort archivePort = mock(ArchivePort.class);
	private final ArchiveWriterPort archiveWriterPort = mock(ArchiveWriterPort.class);
	private final FileMetadataPort fileMetadataPort = mock(FileMetadataPort.class);
	private final FileCapturePort fileCapturePort = mock(FileCapturePort.class);
	private final ArchivePackagingService service = new ArchivePackagingService(
			archivePort, archiveWriterPort, fileMetadataPort, fileCapturePort);

	@Test
	void firstFullArchiveOnAScopeGetsSequenceOneAndNoBase() throws Exception {
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of());
		StoredFile file = file("file-1", "Report.pdf", "");
		when(fileMetadataPort.findAllByOwnerScope("user@example.com")).thenReturn(List.of(file));
		FileCapture capture = new FileCapture(9L, "file-1", "revision-1", Instant.now(), "user@example.com/file-1/x", 10, null);
		when(fileCapturePort.findById(1L)).thenReturn(Optional.of(capture));
		when(archivePort.save(any())).thenAnswer(withId(100L));

		service.packageFullArchive(SCOPE, null);

		ArgumentCaptor<Archive> captor = ArgumentCaptor.forClass(Archive.class);
		verify(archivePort).save(captor.capture());
		assertEquals(1, captor.getValue().sequenceNumber());
		assertEquals(null, captor.getValue().baseArchiveId());
		assertEquals(ArchiveMode.FULL, captor.getValue().mode());
	}

	@Test
	void secondFullRunIncrementsPastTheExistingMaxSequenceNumber() throws Exception {
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of(
				archive(1, null), archive(3, null)));
		when(fileMetadataPort.findAllByOwnerScope("user@example.com")).thenReturn(List.of());
		when(archivePort.save(any())).thenAnswer(withId(100L));

		service.packageFullArchive(SCOPE, null);

		ArgumentCaptor<Archive> captor = ArgumentCaptor.forClass(Archive.class);
		verify(archivePort).save(captor.capture());
		assertEquals(4, captor.getValue().sequenceNumber());
		assertEquals(null, captor.getValue().baseArchiveId());
	}

	@Test
	void onlyEligibleFilesBecomeEntries() throws Exception {
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of());
		StoredFile folder = new StoredFile("folder-1", "user@example.com", "Folder", "", null, FOLDER_MIME_TYPE, false, null, null);
		StoredFile trashed = new StoredFile("file-1", "user@example.com", "Old.pdf", "", null, "application/pdf", true, "r1", 1L);
		StoredFile noCapture = new StoredFile("file-2", "user@example.com", "Pending.pdf", "", null, "application/pdf", false, "r1", null);
		StoredFile eligible = file("file-3", "Report.pdf", "");
		when(fileMetadataPort.findAllByOwnerScope("user@example.com"))
				.thenReturn(List.of(folder, trashed, noCapture, eligible));
		FileCapture capture = new FileCapture(1L, "file-3", "revision-1", Instant.now(), "path/to/file-3", 10, null);
		when(fileCapturePort.findById(1L)).thenReturn(Optional.of(capture));
		when(archivePort.save(any())).thenAnswer(withId(100L));

		service.packageFullArchive(SCOPE, null);

		ArgumentCaptor<List<ArchiveEntry>> entriesCaptor = ArgumentCaptor.forClass(List.class);
		verify(archiveWriterPort).write(any(), entriesCaptor.capture(), any());
		assertEquals(1, entriesCaptor.getValue().size());
		assertEquals("path/to/file-3", entriesCaptor.getValue().getFirst().sourceRelativePath());
	}

	@Test
	void writesTheArchiveBeforeSavingIt() throws Exception {
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of());
		when(fileMetadataPort.findAllByOwnerScope("user@example.com")).thenReturn(List.of());
		when(archivePort.save(any())).thenAnswer(withId(100L));

		service.packageFullArchive(SCOPE, null);

		InOrder order = inOrder(archiveWriterPort, archivePort);
		order.verify(archiveWriterPort).write(any(), any(), any());
		order.verify(archivePort).save(any());
	}

	@Test
	void aWriteFailurePropagatesAndSaveIsNeverCalled() throws Exception {
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of());
		when(fileMetadataPort.findAllByOwnerScope("user@example.com")).thenReturn(List.of());
		doThrow(new IOException("disk full")).when(archiveWriterPort).write(any(), any(), any());

		assertThrows(IllegalStateException.class, () -> service.packageFullArchive(SCOPE, null));

		verify(archivePort, never()).save(any());
	}

	@Test
	void incrementalArchiveChainsToTheLatestExistingArchive() throws Exception {
		Archive first = archive(1, null);
		Archive second = archive(2, 55L);
		Archive secondWithId = new Archive(77L, second.scopeKey(), second.sequenceNumber(), second.baseArchiveId(),
				second.mode(), second.revisionMode(), second.createdAt(), second.archivePath(), second.fromPageToken(),
				second.toPageToken(), second.cancelled());
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of(first, secondWithId));
		when(archivePort.save(any())).thenAnswer(withId(100L));
		FileCapture capture = new FileCapture(1L, "file-1", "revision-1", Instant.now(), "path/file-1", 5, null);
		SyncResult result = new SyncResult(SCOPE, 1, "token-a", "token-b", List.of(), List.of(capture));

		service.packageIncrementalArchive(SCOPE, null, result);

		ArgumentCaptor<Archive> captor = ArgumentCaptor.forClass(Archive.class);
		verify(archivePort).save(captor.capture());
		assertEquals(3, captor.getValue().sequenceNumber());
		assertEquals(77L, captor.getValue().baseArchiveId());
		assertEquals("token-a", captor.getValue().fromPageToken());
		assertEquals("token-b", captor.getValue().toPageToken());
	}

	@Test
	void incrementalEntriesAreDedupedByFileIdKeepingTheLastOccurrence() throws Exception {
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of(archive(1, null)));
		when(archivePort.save(any())).thenAnswer(withId(100L));
		FileCapture first = new FileCapture(1L, "file-1", "revision-1", Instant.now(), "path/first", 5, null);
		FileCapture last = new FileCapture(2L, "file-1", "revision-2", Instant.now(), "path/last", 6, null);
		SyncResult result = new SyncResult(SCOPE, 2, "token-a", "token-b", List.of(), List.of(first, last));

		service.packageIncrementalArchive(SCOPE, null, result);

		ArgumentCaptor<List<ArchiveEntry>> entriesCaptor = ArgumentCaptor.forClass(List.class);
		verify(archiveWriterPort).write(any(), entriesCaptor.capture(), any());
		assertEquals(1, entriesCaptor.getValue().size());
		assertEquals("path/last", entriesCaptor.getValue().getFirst().sourceRelativePath());
	}

	@Test
	void returnsEmptyAndTouchesNeitherPortWhenNothingChangedEvenIfChangeCountIsPositive() throws Exception {
		SyncResult result = new SyncResult(SCOPE, 5, "token-a", "token-b", List.of(), List.of());

		Optional<Archive> archive = service.packageIncrementalArchive(SCOPE, null, result);

		assertTrue(archive.isEmpty());
		verify(archivePort, never()).findByScopeKey(any());
		verify(archiveWriterPort, never()).write(any(), any(), any());
		verify(archivePort, never()).save(any());
	}

	@Test
	void incrementalEventsAndFilesAreCarriedIntoTheManifest() throws Exception {
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of(archive(1, null)));
		when(archivePort.save(any())).thenAnswer(withId(100L));
		FileEvent event = new FileEvent(1L, "file-2", "rename", "Old", "New", Instant.now(), null);
		SyncResult result = new SyncResult(SCOPE, 1, "token-a", "token-b", List.of(event), List.of());

		service.packageIncrementalArchive(SCOPE, null, result);

		ArgumentCaptor<org.nm.gdrive_backup.domain.model.ArchiveManifest> manifestCaptor =
				ArgumentCaptor.forClass(org.nm.gdrive_backup.domain.model.ArchiveManifest.class);
		verify(archiveWriterPort).write(any(), any(), manifestCaptor.capture());
		assertEquals(1, manifestCaptor.getValue().events().size());
		assertEquals("file-2", manifestCaptor.getValue().events().getFirst().fileId());
		assertFalse(manifestCaptor.getValue().events().isEmpty());
	}

	private static StoredFile file(String fileId, String name, String parents) {
		return new StoredFile(fileId, "user@example.com", name, parents, null, "application/pdf", false, "revision-1", 1L);
	}

	private static Archive archive(int sequenceNumber, Long baseArchiveId) {
		return new Archive((long) sequenceNumber, "user@example.com", sequenceNumber, baseArchiveId, ArchiveMode.FULL,
				RevisionMode.LATEST_ONLY, Instant.now(), "archives/scope/archive.zip", null, null, false);
	}

	private static org.mockito.stubbing.Answer<Archive> withId(long id) {
		return invocation -> {
			Archive archive = invocation.getArgument(0);
			return new Archive(id, archive.scopeKey(), archive.sequenceNumber(), archive.baseArchiveId(),
					archive.mode(), archive.revisionMode(), archive.createdAt(), archive.archivePath(),
					archive.fromPageToken(), archive.toPageToken(), archive.cancelled());
		};
	}
}
