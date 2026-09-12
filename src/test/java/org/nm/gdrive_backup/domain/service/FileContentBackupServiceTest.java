package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nm.gdrive_backup.domain.model.FileVersion;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.port.out.DriveContentPort;
import org.nm.gdrive_backup.domain.port.out.FileVersionPort;
import org.nm.gdrive_backup.domain.port.out.VersionStoragePort;

class FileContentBackupServiceTest {

	private static final ServiceAccountAccess ACCESS = new ServiceAccountAccess(
			UUID.randomUUID(), "user@example.com", Instant.now().plusSeconds(3600), Set.of("drive.readonly"));

	@TempDir
	Path temporaryDirectory;

	@Test
	void exportsGoogleDocsToDocxAndPersistsVersion() throws Exception {
		DriveContentPort contentPort = mock(DriveContentPort.class);
		VersionStoragePort storagePort = mock(VersionStoragePort.class);
		FileVersionPort versionPort = mock(FileVersionPort.class);
		Path storedPath = temporaryDirectory.resolve("Report.docx");
		Files.writeString(storedPath, "docx-content");
		StoredFile file = new StoredFile("file-1", "user@example.com", "Report", "root", null,
				"application/vnd.google-apps.document", false, "revision-1", null);
		when(contentPort.export(ACCESS, "file-1",
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
				.thenReturn(new ByteArrayInputStream(new byte[] { 1 }));
		when(storagePort.store(eq("user@example.com"), eq("file-1"), eq("revision-1"), eq("Report.docx"),
				org.mockito.ArgumentMatchers.any())).thenReturn(storedPath);
		when(versionPort.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

		FileVersion version = new FileContentBackupService(contentPort, storagePort, versionPort)
				.backup(ACCESS, file);

		assertEquals("revision-1", version.revisionId());
		verify(contentPort).export(eq(ACCESS), eq("file-1"),
				eq("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
		verify(versionPort).save(org.mockito.ArgumentMatchers.any());
	}
}