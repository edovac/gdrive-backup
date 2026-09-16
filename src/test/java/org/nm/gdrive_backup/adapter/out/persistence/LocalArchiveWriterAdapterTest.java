package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nm.gdrive_backup.domain.model.ArchiveEntry;
import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.RevisionMode;

class LocalArchiveWriterAdapterTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void writesEntriesAndAnEmbeddedManifest() throws Exception {
		LocalCaptureStorageAdapter captureStorage = new LocalCaptureStorageAdapter(temporaryDirectory);
		Files.createDirectories(temporaryDirectory.resolve("user@example.com/file-1"));
		Files.writeString(temporaryDirectory.resolve("user@example.com/file-1/Report.pdf"), "report-content");
		LocalArchiveWriterAdapter adapter = new LocalArchiveWriterAdapter(captureStorage);
		ArchiveManifest manifest = new ArchiveManifest("user@example.com", ArchiveMode.FULL, RevisionMode.LATEST_ONLY,
				1, null, Instant.parse("2026-09-16T10:00:00Z"), null, null,
				List.of(new ArchiveManifest.ManifestFile("file-1", "Report.pdf", "revision-1", 14)), List.of());
		List<ArchiveEntry> entries = List.of(new ArchiveEntry("Report.pdf", "user@example.com/file-1/Report.pdf"));

		adapter.write("archives/scope/archive-0001-full.zip", entries, manifest);

		Path target = temporaryDirectory.resolve("archives/scope/archive-0001-full.zip");
		assertTrue(Files.exists(target));
		try (ZipFile zip = new ZipFile(target.toFile())) {
			ZipEntry reportEntry = zip.getEntry("Report.pdf");
			assertNotNull(reportEntry);
			assertEquals("report-content",
					new String(zip.getInputStream(reportEntry).readAllBytes(), StandardCharsets.UTF_8));

			ZipEntry manifestEntry = zip.getEntry("manifest.json");
			assertNotNull(manifestEntry);
			String manifestJson = new String(zip.getInputStream(manifestEntry).readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(manifestJson.contains("\"scope_key\""));
			assertTrue(manifestJson.contains("user@example.com"));
			assertTrue(manifestJson.contains("\"sequence_number\": 1"));
			assertTrue(manifestJson.contains("\"file_id\": \"file-1\""));
		}
	}

	@Test
	void createsMissingParentDirectories() throws Exception {
		LocalCaptureStorageAdapter captureStorage = new LocalCaptureStorageAdapter(temporaryDirectory);
		LocalArchiveWriterAdapter adapter = new LocalArchiveWriterAdapter(captureStorage);

		adapter.write("archives/deeply/nested/scope/archive-0001-full.zip", List.of(), emptyManifest());

		assertTrue(Files.exists(temporaryDirectory.resolve("archives/deeply/nested/scope/archive-0001-full.zip")));
	}

	@Test
	void leavesNoStrayTempFileAfterSuccess() throws Exception {
		LocalCaptureStorageAdapter captureStorage = new LocalCaptureStorageAdapter(temporaryDirectory);
		LocalArchiveWriterAdapter adapter = new LocalArchiveWriterAdapter(captureStorage);

		adapter.write("archives/scope/archive-0001-full.zip", List.of(), emptyManifest());

		try (var files = Files.list(temporaryDirectory.resolve("archives/scope"))) {
			List<Path> remaining = files.toList();
			assertEquals(1, remaining.size());
			assertEquals("archive-0001-full.zip", remaining.getFirst().getFileName().toString());
		}
	}

	@Test
	void aMissingSourceFileLeavesNoPartialFileAndCleansUpTheTemp() {
		LocalCaptureStorageAdapter captureStorage = new LocalCaptureStorageAdapter(temporaryDirectory);
		LocalArchiveWriterAdapter adapter = new LocalArchiveWriterAdapter(captureStorage);
		List<ArchiveEntry> entries = List.of(new ArchiveEntry("missing.pdf", "does/not/exist.pdf"));

		assertThrows(IOException.class,
				() -> adapter.write("archives/scope/archive-0001-full.zip", entries, emptyManifest()));

		Path scopeDirectory = temporaryDirectory.resolve("archives/scope");
		assertFalse(Files.exists(scopeDirectory.resolve("archive-0001-full.zip")));
		if (Files.exists(scopeDirectory)) {
			try (var files = Files.list(scopeDirectory)) {
				assertEquals(0, files.count());
			} catch (IOException exception) {
				throw new UncheckedIOException(exception);
			}
		}
	}

	@Test
	void readsTheCaptureStorageRootFreshAcrossASwitch() throws Exception {
		LocalCaptureStorageAdapter captureStorage = new LocalCaptureStorageAdapter(temporaryDirectory.resolve("first"));
		LocalArchiveWriterAdapter adapter = new LocalArchiveWriterAdapter(captureStorage);
		Path secondRoot = temporaryDirectory.resolve("second");
		captureStorage.switchTo(secondRoot);

		adapter.write("archives/scope/archive-0001-full.zip", List.of(), emptyManifest());

		assertTrue(Files.exists(secondRoot.resolve("archives/scope/archive-0001-full.zip")));
		assertFalse(Files.exists(temporaryDirectory.resolve("first/archives/scope/archive-0001-full.zip")));
	}

	private static ArchiveManifest emptyManifest() {
		return new ArchiveManifest("user@example.com", ArchiveMode.FULL, RevisionMode.LATEST_ONLY, 1, null,
				Instant.parse("2026-09-16T10:00:00Z"), null, null, List.of(), List.of());
	}
}
