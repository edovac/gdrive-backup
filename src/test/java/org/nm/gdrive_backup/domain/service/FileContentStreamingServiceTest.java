package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.DriveExportLimitException;
import org.nm.gdrive_backup.domain.model.FetchedFile;
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
	void fetchStagesTheContentWithoutWritingAnythingToTheArchive() throws Exception {
		StoredFile file = file("file-1", "Report.pdf", "application/pdf");
		when(contentPort.download(ACCESS, "file-1")).thenReturn(new ByteArrayInputStream(new byte[] { 1, 2, 3 }));

		try (var session = sessions.open("archives/x.zip")) {
			FetchedFile fetched = service.fetch(ACCESS, file, session);

			assertEquals(3, fetched.content().size());
			assertNull(fetched.exportMimeType());
			assertNull(fetched.fallbackFromExtension());
			assertTrue(sessions.entries.isEmpty(), "nothing is written until the archive writer takes the content");
			assertEquals(1, sessions.staged.size());
			assertFalse(sessions.allStagedReleased());

			StreamedFile streamed = service.write(file, fetched, session, "Docs/Report.pdf");

			assertEquals("Docs/Report.pdf", streamed.capture().entryName());
			assertEquals(3, streamed.capture().sizeBytes());
			assertEquals(3, sessions.entries.get("Docs/Report.pdf").length);
			assertTrue(sessions.allStagedReleased(), "writing releases the staged content");
		}
	}

	@Test
	void aPdfFallbackIsRenamedWhenTheFetchedContentIsWritten() throws Exception {
		StoredFile file = file("file-1", "Report", "application/vnd.google-apps.document");
		when(contentPort.export(ACCESS, "file-1", DOCX)).thenThrow(new DriveExportLimitException("too large", null));
		when(contentPort.export(ACCESS, "file-1", "application/pdf"))
				.thenReturn(new ByteArrayInputStream(new byte[] { 1 }));

		try (var session = sessions.open("archives/x.zip")) {
			// Another file already took the PDF name, which only the writer can know about.
			FetchedFile fetched = service.fetch(ACCESS, file, session);
			session.writeEntry("Docs/Report.pdf", new ByteArrayInputStream(new byte[] { 9 }));

			assertEquals("application/pdf", fetched.exportMimeType());
			assertEquals(".docx", fetched.fallbackFromExtension());
			StreamedFile streamed = service.write(file, fetched, session, "Docs/Report.docx");

			assertEquals("Docs/Report (2).pdf", streamed.capture().entryName());
			assertEquals("application/pdf", streamed.exportMimeType());
		}
	}

	@Test
	void fetchLeavesNothingStagedWhenTheExportFailsOutright() throws Exception {
		StoredFile file = file("file-1", "Report", "application/vnd.google-apps.document");
		when(contentPort.export(ACCESS, "file-1", DOCX)).thenThrow(new DriveExportLimitException("too large", null));
		when(contentPort.export(ACCESS, "file-1", "application/pdf"))
				.thenThrow(new DriveExportLimitException("still too large", null));

		try (var session = sessions.open("archives/x.zip")) {
			assertThrows(IllegalStateException.class, () -> service.fetch(ACCESS, file, session));

			assertTrue(sessions.staged.isEmpty());
		}
	}

	@Test
	void fetchSurfacesAnIoFailureWhileStagingAsAnIllegalStateAndStagesNothing() throws Exception {
		StoredFile file = file("file-1", "Report.pdf", "application/pdf");
		when(contentPort.download(ACCESS, "file-1")).thenReturn(new InputStream() {
			@Override
			public int read() throws IOException {
				throw new IOException("connection reset");
			}
		});

		try (var session = sessions.open("archives/x.zip")) {
			assertThrows(IllegalStateException.class, () -> service.fetch(ACCESS, file, session));

			assertTrue(sessions.staged.isEmpty());
		}
	}

	@Test
	void fetchTellsTheListenerHowManyBytesHaveArrivedEndingWithTheFullSize() throws Exception {
		StoredFile file = file("file-1", "Big.pdf", "application/pdf");
		when(contentPort.download(ACCESS, "file-1")).thenReturn(new ByteArrayInputStream(new byte[300_000]));
		List<Long> reported = new ArrayList<>();

		try (var session = sessions.open("archives/x.zip")) {
			FetchedFile fetched = service.fetch(ACCESS, file, session, reported::add);

			assertEquals(300_000, fetched.content().size());
			assertFalse(reported.isEmpty());
			assertEquals(300_000L, reported.getLast());
			for (int i = 1; i < reported.size(); i++) {
				assertTrue(reported.get(i) >= reported.get(i - 1), "totals never go down: " + reported);
			}
		}
	}

	@Test
	void aPdfFallbackStartsCountingAgainFromTheNewDownload() throws Exception {
		StoredFile file = file("file-1", "Report", "application/vnd.google-apps.document");
		when(contentPort.export(ACCESS, "file-1", DOCX)).thenThrow(new DriveExportLimitException("too large", null));
		when(contentPort.export(ACCESS, "file-1", "application/pdf"))
				.thenReturn(new ByteArrayInputStream(new byte[5]));
		List<Long> reported = new ArrayList<>();

		try (var session = sessions.open("archives/x.zip")) {
			service.fetch(ACCESS, file, session, reported::add);
		}

		assertEquals(List.of(5L), reported, "the failed export streamed nothing, so only the PDF is counted");
	}

	@Test
	void theThreeArgumentFetchStillWorksWithoutAListener() throws Exception {
		StoredFile file = file("file-1", "Report.pdf", "application/pdf");
		when(contentPort.download(ACCESS, "file-1")).thenReturn(new ByteArrayInputStream(new byte[] { 1, 2 }));

		try (var session = sessions.open("archives/x.zip")) {
			assertEquals(2, service.fetch(ACCESS, file, session).content().size());
		}
	}

	@Test
	void fetchValidatesTheFileJustLikeStream() throws Exception {
		try (var session = sessions.open("archives/x.zip")) {
			assertThrows(IllegalArgumentException.class, () -> service.fetch(ACCESS,
					new StoredFile("f", "u", "n", "", null, "application/pdf", false, null, null), session));
			assertThrows(IllegalArgumentException.class, () -> service.fetch(ACCESS, null, session));
		}
	}

	@Test
	void fetchesFromSeveralThreadsAtOnceThenWritesInTheOrderTheWriterChooses() throws Exception {
		List<StoredFile> files = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			StoredFile file = file("file-" + i, "F" + i + ".pdf", "application/pdf");
			files.add(file);
			when(contentPort.download(ACCESS, file.fileId())).thenReturn(new ByteArrayInputStream(new byte[] { (byte) i }));
		}
		ExecutorService executor = Executors.newFixedThreadPool(4);
		try (var session = sessions.open("archives/x.zip")) {
			List<Future<FetchedFile>> fetched = new ArrayList<>();
			for (StoredFile file : files) {
				fetched.add(executor.submit(() -> service.fetch(ACCESS, file, session)));
			}
			for (int i = 0; i < files.size(); i++) {
				service.write(files.get(i), fetched.get(i).get(), session, "F" + i + ".pdf");
			}

			assertEquals(List.of("F0.pdf", "F1.pdf", "F2.pdf", "F3.pdf", "F4.pdf", "F5.pdf", "F6.pdf", "F7.pdf"),
					List.copyOf(sessions.entries.keySet()));
			assertTrue(sessions.allStagedReleased());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void storesContentThatIsAlreadyCompressedAndCompressesTheRest() throws Exception {
		Map<String, Boolean> expectedCompressed = new LinkedHashMap<>();
		expectedCompressed.put("application/pdf", false);
		expectedCompressed.put("application/zip", false);
		expectedCompressed.put("image/jpeg", false);
		expectedCompressed.put("image/png", false);
		expectedCompressed.put("video/mp4", false);
		expectedCompressed.put(DOCX, false);
		expectedCompressed.put("text/plain", true);
		expectedCompressed.put("application/json", true);
		expectedCompressed.put("text/csv", true);
		expectedCompressed.put("image/bmp", true);
		expectedCompressed.put("application/octet-stream", true);

		try (var session = sessions.open("archives/x.zip")) {
			int index = 0;
			for (String mimeType : expectedCompressed.keySet()) {
				String id = "file-" + index;
				when(contentPort.download(ACCESS, id)).thenReturn(new ByteArrayInputStream(new byte[] { 1 }));
				service.stream(ACCESS, file(id, "n", mimeType), session, "entry-" + index);
				index++;
			}
		}

		int index = 0;
		for (Map.Entry<String, Boolean> expected : expectedCompressed.entrySet()) {
			assertEquals(expected.getValue(), sessions.compressedByEntry.get("entry-" + index),
					expected.getKey() + " compressed?");
			index++;
		}
	}

	@Test
	void storesGoogleNativeFilesBecauseTheirExportsAreCompressedContainers() throws Exception {
		for (var nativeType : List.of("application/vnd.google-apps.document",
				"application/vnd.google-apps.spreadsheet", "application/vnd.google-apps.presentation")) {
			String exportType = switch (nativeType) {
				case "application/vnd.google-apps.document" -> DOCX;
				case "application/vnd.google-apps.spreadsheet" ->
					"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
				default -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
			};
			when(contentPort.export(ACCESS, "file-" + nativeType, exportType))
					.thenReturn(new ByteArrayInputStream(new byte[] { 1 }));

			try (var session = sessions.open("archives/x.zip")) {
				service.stream(ACCESS, file("file-" + nativeType, "n", nativeType), session, "entry");
			}

			assertEquals(false, sessions.compressedByEntry.get("entry"), nativeType + " export is stored");
		}
	}

	@Test
	void aPdfFallbackIsStoredToo() throws Exception {
		StoredFile file = file("file-1", "Report", "application/vnd.google-apps.document");
		when(contentPort.export(ACCESS, "file-1", DOCX)).thenThrow(new DriveExportLimitException("too large", null));
		when(contentPort.export(ACCESS, "file-1", "application/pdf"))
				.thenReturn(new ByteArrayInputStream(new byte[] { 1 }));

		try (var session = sessions.open("archives/x.zip")) {
			service.stream(ACCESS, file, session, "Report.docx");
		}

		assertEquals(false, sessions.compressedByEntry.get("Report.pdf"));
	}

	@Test
	void recognisesAlreadyCompressedMimeTypes() {
		for (String compressed : List.of("application/pdf", "application/zip", "application/gzip",
				"application/x-7z-compressed", "image/jpeg", "image/png", "image/webp", "image/heic", "video/mp4",
				"video/quicktime", "audio/mpeg", "audio/mp4", "audio/ogg", DOCX,
				"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
				"application/vnd.openxmlformats-officedocument.presentationml.presentation",
				"application/vnd.oasis.opendocument.text")) {
			assertTrue(FileContentStreamingService.isAlreadyCompressed(compressed), compressed);
		}
		for (String compressible : List.of("text/plain", "text/html", "text/csv", "application/json",
				"application/xml", "image/bmp", "image/svg+xml", "audio/wav", "audio/x-wav", "application/msword",
				"application/octet-stream", "")) {
			assertFalse(FileContentStreamingService.isAlreadyCompressed(compressible), compressible);
		}
		assertFalse(FileContentStreamingService.isAlreadyCompressed(null));
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
