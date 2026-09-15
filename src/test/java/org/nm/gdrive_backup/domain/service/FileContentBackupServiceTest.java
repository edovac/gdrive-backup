package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.FileCapture;
import org.nm.gdrive_backup.domain.model.DriveExportLimitException;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StoredCapture;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.port.out.DriveContentPort;
import org.nm.gdrive_backup.domain.port.out.FileCapturePort;
import org.nm.gdrive_backup.domain.port.out.CaptureStoragePort;

class FileContentBackupServiceTest {

	private static final ServiceAccountAccess ACCESS = new ServiceAccountAccess(
			UUID.randomUUID(), "user@example.com", Instant.now().plusSeconds(3600), Set.of("drive.readonly"));

	@Test
	void exportsGoogleDocsToDocxAndPersistsCapture() throws Exception {
		DriveContentPort contentPort = mock(DriveContentPort.class);
		CaptureStoragePort storagePort = mock(CaptureStoragePort.class);
		FileCapturePort capturePort = mock(FileCapturePort.class);
		StoredFile file = new StoredFile("file-1", "user@example.com", "Report", "root", null,
				"application/vnd.google-apps.document", false, "revision-1", null);
		when(contentPort.export(ACCESS, "file-1",
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
				.thenReturn(new ByteArrayInputStream(new byte[] { 1 }));
		when(storagePort.store(eq("user@example.com"), eq("file-1"), eq("Report.docx"),
				org.mockito.ArgumentMatchers.any()))
				.thenReturn(new StoredCapture(Path.of("user@example.com/file-1/Report.docx"), 12));
		when(capturePort.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

		FileCapture capture = new FileContentBackupService(contentPort, storagePort, capturePort)
				.backup(ACCESS, file);

		assertEquals("revision-1", capture.revisionId());
		assertEquals("user@example.com/file-1/Report.docx", capture.localPath());
		assertEquals(12, capture.sizeBytes());
		verify(contentPort).export(eq(ACCESS), eq("file-1"),
				eq("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
		verify(capturePort).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void fallsBackToPdfWhenOfficeExportExceedsLimit() throws Exception {
		DriveContentPort contentPort = mock(DriveContentPort.class);
		CaptureStoragePort storagePort = mock(CaptureStoragePort.class);
		FileCapturePort capturePort = mock(FileCapturePort.class);
		StoredFile file = new StoredFile("file-1", "user@example.com", "Report", "root", null,
				"application/vnd.google-apps.document", false, "revision-1", null);
		when(contentPort.export(ACCESS, "file-1",
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
				.thenThrow(new DriveExportLimitException("too large", null));
		when(contentPort.export(ACCESS, "file-1", "application/pdf"))
				.thenReturn(new ByteArrayInputStream(new byte[] { 1 }));
		when(storagePort.store(eq("user@example.com"), eq("file-1"), eq("Report.pdf"),
				org.mockito.ArgumentMatchers.any()))
				.thenReturn(new StoredCapture(Path.of("user@example.com/file-1/Report.pdf"), 11));
		when(capturePort.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

		new FileContentBackupService(contentPort, storagePort, capturePort).backup(ACCESS, file);

		verify(contentPort).export(ACCESS, "file-1", "application/pdf");
	}
}
