package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.nm.gdrive_backup.domain.model.DriveExportLimitException;
import org.nm.gdrive_backup.domain.model.FileCapture;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.model.StreamedFile;
import org.nm.gdrive_backup.domain.port.out.DriveContentPort;

class FileContentStreamingServiceTest {

	private static final ServiceAccountAccess ACCESS = new ServiceAccountAccess(
			UUID.randomUUID(), "user@example.com", Instant.now().plusSeconds(3600), Set.of("drive.readonly"));
	private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

	private final DriveContentPort contentPort = mock(DriveContentPort.class);
	private final RecordingArchiveSessionPort sessions = new RecordingArchiveSessionPort(new ArrayList<>());
	private final FileContentStreamingService service = new FileContentStreamingService(contentPort);

	@Test
	void streamsARegularFileIntoTheNamedEntryWithoutPersistingAnything() throws Exception {
		StoredFile file = file("file-1", "Report.pdf", "application/pdf");
		when(contentPort.download(ACCESS, "file-1")).thenReturn(new ByteArrayInputStream(new byte[] { 1, 2, 3 }));

		StreamedFile streamed;
		try (var session = sessions.open("archives/x.zip")) {
			streamed = service.stream(ACCESS, file, session, "Docs/Report.pdf");
		}
		FileCapture capture = streamed.capture();

		assertEquals(3, sessions.entries.get("Docs/Report.pdf").length);
		assertEquals("Docs/Report.pdf", capture.entryName());
		assertEquals("revision-1", capture.revisionId());
		assertEquals(3, capture.sizeBytes());
		assertNull(capture.id());
		assertNull(capture.archiveId());
		assertNull(streamed.exportMimeType());
	}

	@Test
	void exportsGoogleDocsToDocx() throws Exception {
		StoredFile file = file("file-1", "Report", "application/vnd.google-apps.document");
		when(contentPort.export(ACCESS, "file-1", DOCX)).thenReturn(new ByteArrayInputStream(new byte[] { 1 }));

		try (var session = sessions.open("archives/x.zip")) {
			StreamedFile streamed = service.stream(ACCESS, file, session, "Report.docx");

			assertEquals("Report.docx", streamed.capture().entryName());
			assertEquals(DOCX, streamed.exportMimeType());
		}
		verify(contentPort, never()).download(ACCESS, "file-1");
	}

	@Test
	void fallsBackToPdfWhenTheOfficeExportExceedsTheLimitAndRenamesTheEntry() throws Exception {
		StoredFile file = file("file-1", "Report", "application/vnd.google-apps.document");
		when(contentPort.export(ACCESS, "file-1", DOCX)).thenThrow(new DriveExportLimitException("too large", null));
		when(contentPort.export(ACCESS, "file-1", "application/pdf"))
				.thenReturn(new ByteArrayInputStream(new byte[] { 1 }));

		try (var session = sessions.open("archives/x.zip")) {
			StreamedFile streamed = service.stream(ACCESS, file, session, "Docs/Report.docx");

			assertEquals("Docs/Report.pdf", streamed.capture().entryName());
			assertEquals("application/pdf", streamed.exportMimeType());
			assertTrue(session.containsEntry("Docs/Report.pdf"));
			assertTrue(!session.containsEntry("Docs/Report.docx"));
		}
	}

	@Test
	void theFallbackEntryGetsASuffixWhenThePdfNameIsAlreadyTaken() throws Exception {
		StoredFile file = file("file-1", "Report", "application/vnd.google-apps.document");
		when(contentPort.export(ACCESS, "file-1", DOCX)).thenThrow(new DriveExportLimitException("too large", null));
		when(contentPort.export(ACCESS, "file-1", "application/pdf"))
				.thenReturn(new ByteArrayInputStream(new byte[] { 1 }));

		try (var session = sessions.open("archives/x.zip")) {
			session.writeEntry("Report.pdf", new ByteArrayInputStream(new byte[] { 9 }));

			FileCapture capture = service.stream(ACCESS, file, session, "Report.docx").capture();

			assertEquals("Report (2).pdf", capture.entryName());
		}
	}

	@Test
	void failsWhenThePdfFallbackAlsoExceedsTheLimit() throws Exception {
		StoredFile file = file("file-1", "Report", "application/vnd.google-apps.document");
		when(contentPort.export(ACCESS, "file-1", DOCX)).thenThrow(new DriveExportLimitException("too large", null));
		when(contentPort.export(ACCESS, "file-1", "application/pdf"))
				.thenThrow(new DriveExportLimitException("still too large", null));

		try (var session = sessions.open("archives/x.zip")) {
			assertThrows(IllegalStateException.class, () -> service.stream(ACCESS, file, session, "Report.docx"));
		}
	}

	@Test
	void anIoFailureWhileDownloadingSurfacesAsAnIllegalState() throws Exception {
		StoredFile file = file("file-1", "Report.pdf", "application/pdf");
		when(contentPort.download(ACCESS, "file-1")).thenThrow(new IOException("connection reset"));

		try (var session = sessions.open("archives/x.zip")) {
			assertThrows(IllegalStateException.class, () -> service.stream(ACCESS, file, session, "Report.pdf"));
		}
	}

	@Test
	void requiresAFileIdAndARevision() throws Exception {
		try (var session = sessions.open("archives/x.zip")) {
			assertThrows(IllegalArgumentException.class, () -> service.stream(ACCESS,
					new StoredFile("", "u", "n", "", null, "application/pdf", false, "r", null), session, "n"));
			assertThrows(IllegalArgumentException.class, () -> service.stream(ACCESS,
					new StoredFile("f", "u", "n", "", null, "application/pdf", false, null, null), session, "n"));
		}
	}

	@Test
	void knowsWhichMimeTypesExportWithAnExtension() {
		assertEquals(".docx", FileContentStreamingService.exportExtensionFor("application/vnd.google-apps.document").orElseThrow());
		assertTrue(FileContentStreamingService.exportExtensionFor("application/pdf").isEmpty());
	}

	@Test
	void onlyFilesWithARevisionAndBackableContentAreBackable() {
		assertTrue(FileContentStreamingService.hasBackableContent(file("f", "n", "application/pdf")));
		assertTrue(FileContentStreamingService.hasBackableContent(file("f", "n", "application/vnd.google-apps.document")));
		assertTrue(!FileContentStreamingService.hasBackableContent(file("f", "n", "application/vnd.google-apps.folder")));
		assertTrue(!FileContentStreamingService.hasBackableContent(file("f", "n", "application/vnd.google-apps.form")));
		assertTrue(!FileContentStreamingService.hasBackableContent(file("f", "n", "application/vnd.google-apps.shortcut")));
		assertTrue(!FileContentStreamingService.hasBackableContent(
				new StoredFile("f", "u", "n", "", null, "application/pdf", false, null, null)));
	}

	private static StoredFile file(String id, String name, String mimeType) {
		return new StoredFile(id, "user@example.com", name, "", null, mimeType, false, "revision-1", null);
	}
}
